(ns charly.aws-lambda-deploy
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom :refer [<defn gol <?]]
            [rx.jvm.ops.build :as build]
            [charly.config :as config]
            [clojure.string :as str]
            [clj-http.client :as hc]
            [rx.aws :as aws]))

(defn expand-home [s]
  (if (.startsWith s "~")
    (str/replace-first s "~" (System/getProperty "user.home"))
    s))

(defn cljs-build-params
  "Build spec to cljs build api parameters"
  [{:keys [::build/output-path
           ::build/main-namespace
           ::build/cljs-compiler]}]
  {:source-paths ["src/cljc"
                  "src/cljs"
                  "src/cljs-node"]
   :compiler (merge
               {:output-to (str output-path "/cljs/awslambda.js")
                :output-dir (str output-path "/cljs")
                :optimizations :simple
                :source-map false
                :main (str main-namespace)
                :parallel-build true
                :verbose false
                :target :nodejs
                :foreign-libs build/common-foreign-libs
                :externs ["resources/dexie/dexie.ext.js"
                          "resources/tonejs/Tone.ext.js"]
                :warnings {:undeclared-ns true
                           :unprovided true
                           :undeclared-var true
                           :munged-namespace true
                           :ns-var-clash true}
                :warning-handlers
                #_nil
                [cljs.analyzer.api/default-warning-handler
                 (fn [warning-type env extra]
                   (when (and (get {:undeclared-ns true
                                    :unprovided true
                                    :undeclared-var true
                                    :munged-namespace true
                                    :ns-var-clash true}
                                warning-type)
                              (not (:macro-present? extra))
                              (not (= 'thisfn (:suffix extra))))
                     (let [si (cljs.analyzer/source-info env)]
                       (anom/throw-anom
                         {:desc (str "Error compiling " (:file si))
                          ::build/source-info si}))
                     #_(throw
                         (cljs.analyzer/error
                           env
                           (cljs.analyzer/error-message warning-type extra)))))]}
               cljs-compiler)})

(defn copy-cljs-assets [{:keys [::build/output-path]}]
  (build/report-and-run
    ["cp"
     (str output-path "/cljs/awslambda.js")
     (str output-path "/build/")]))

(defn create-build-directory [{:keys [::build/output-path]}]
  (build/mkdir (str output-path "/build")))

(defn copy-node-modules [{:keys [::build/output-path]}]
  (let [package-json (ks/from-json (slurp "./package.json") true)
        deps (get package-json "dependencies")
        lib-paths (->> deps
                       (map (fn [[s _]]
                              (str "node_modules/" s))))]
    (build/mkdir (str output-path "/build/node_modules"))
    (doseq [path lib-paths]
      (build/report-and-run
        ["cp" "-R" path (str output-path "/build/node_modules/")]))))

(defn create-zip [{:keys [::build/output-path]}]
  (println " * Creating zip file...")
  (build/mkdir (str output-path "/dist"))
  (build/zip
    (str output-path "/build")
    "../dist/awslambda.zip"))

(defn build-lambda! [spec]
  (try
    (build/clean-output-path spec)
    (create-build-directory spec)
    (println " * Compiling cljs...")
    (build/compile-cljs
      (cljs-build-params spec))
    (copy-cljs-assets spec)
    (copy-node-modules spec)
    (create-zip spec)
    (println "Build completed:" (str (build/cwd) "/" (::build/output-path spec)))
    (catch Exception e
      (build/report-build-error e))
    (catch AssertionError e
      (build/report-build-error e))))


;; Deploy

(defn lambda-fn-name [{:keys [::aws/lambda-fn-id ::aws/lambda-fn-name :fn-name] :as spec}]
  (if lambda-fn-id
    (->> [(namespace lambda-fn-id)
          (name lambda-fn-id)]
         (remove nil?)
         (interpose "--")
         (apply str))
    (or lambda-fn-name
        fn-name)))

(defn lambda-create-fn-payload
  [{:keys [:description
           :env
           :handler
           :memory-mb
           :publish?
           :role-arn
           :runtime-name
           :tags
           :timeout-seconds
           :tracing-config
           :kms-key-arn
           :vpc-config
           :zip-path]
    :as spec}]
  (merge
    {:code {:zipFile (-> zip-path
                         aws/path->zipfile)}
     :description description
     :environment {:variables env}
     :functionName (lambda-fn-name spec)
     :memorySize memory-mb
     :publish publish?
     :role role-arn
     :runtime runtime-name
     :tags tags
     :timeout timeout-seconds
     :handler handler}
    (when kms-key-arn
      {:kmsKeyArn kms-key-arn})
    (when tracing-config
      {:tracingConfig {:mode tracing-config}})
    (when vpc-config
      {:vpcConfig vpc-config})
    {:zip-path zip-path}))

(defn lambda-update-fn-payload [spec]
  (dissoc
    (lambda-create-fn-payload spec)
    :code
    :publish))

(defn report-create-function [spec]
  (println " * Creating function" (lambda-fn-name spec))
  (let [[res err exc :as pl] (aws/create-function
                               (:creds spec)
                               (lambda-create-fn-payload spec))]
    (when err
      (ks/pp exc)
      (println " ! Error creating function" err))
    pl))

(defn zip-byte-buffer [zip-path]
  (aws/path->zipfile zip-path))

(defn report-update-function-code [{:keys [zip-path]
                                    :as spec}]
  (let [lfn-name (lambda-fn-name spec)]
    (println " * Updating function code" lfn-name)
    (let [[res err exc] (aws/update-function-code
                          (:creds spec)
                          {:functionName lfn-name
                           :zipFile (zip-byte-buffer zip-path)})]
      (when err
        (println " ! Error updating function code" err exc))
      [res err])))

(defn report-update-function-configuration [{:keys [zip-path]
                                             :as spec}]
  (let [lfn-name (lambda-fn-name spec)]
    (println " * Updating function config" lfn-name)
    (let [[res err exc] (aws/update-function-configuration
                          (:creds spec)
                          (lambda-update-fn-payload spec))]
      (when err
        (println " ! Error updating function configuration" err exc))
      [res err])))

(defn report-create-or-update-function
  [spec]
  (let [function-name (lambda-fn-name spec)]
    (println " * Checking for existing" function-name)
    (let [[exists?] (aws/get-function
                      (:creds spec)
                      {:functionName function-name})]
      (if exists?
        (do
          (report-update-function-code spec)
          (report-update-function-configuration spec))
        (report-create-function spec)))))

(defn deploy-lambda! [spec]
  (try
    (ks/pp
      (report-create-or-update-function spec))
    (catch Exception e
      (build/report-build-error e))
    (catch AssertionError e
      (build/report-build-error e))))

(defn read-creds [env]
  (let [s (slurp
            (-> env
                :api-lambda
                :local-creds-path
                expand-home))]
    (when s
      (ks/edn-read-string s))))

(defn deploy! [env opts]
  (let [creds (read-creds env)
        {:keys [env-path]} (:api-lambda env)
        env-vars (config/parse-env (slurp env-path))]
    (ks/spy "ENV" env-vars)
    (deploy-lambda!
      (merge
        (:api-lambda env)
        {:env env-vars}
        {:creds creds
         :zip-path (config/concat-paths
                     [(:api-prod-output-path env) "../awslambda.zip"])}))))

(comment

  (ks/pp (config/read-env "./charly.edn"))

  (let [env (config/read-env "./charly.edn")
        creds (read-creds env)]
    (deploy-lambda!
      (merge
        (:api-lambda env)
        {:creds creds
         :zip-path (config/concat-paths
                     [(:api-prod-output-path env) "../awslambda.zip"])})))

  (prn "HI")

  )

(defn verify-endpoint [{:keys [::aws/api-gateway-invoke-url]}]
  (let [res (hc/get api-gateway-invoke-url)]
    (println "Body")
    (println (:body res))
    (println "Status" (:status res))))

(defn test-good-build []
  (build-lambda!
    {::build/output-path "target/test-build"
     ::build/main-namespace "rx.node.lambda"}))

(defn test-cljs-error []
  (build-lambda!
    {::build/output-path "target/test-build"
     ::build/main-namespace "rx.node.ops.test.cljs-build-error"}))

(defn test-clean-error []
  (build-lambda!
    {::build/output-path "/nopath"
     ::build/main-namespace "rx.node.ops.test.cljs-build-error"}))

(defn test-oc-build []
  (build-lambda!
    {::build/output-path "target/oc-lambda"
     ::build/main-namespace "oc.node.awslambda"}))

(defn test-oc-deploy []
  (deploy-lambda!
    (ks/spy
      (merge
        {::aws/lambda-fn-id :onlycrayons_apiserver
         ::aws/lambda-runtime-name "nodejs12.x"
         ::aws/lambda-memory-mb 512
         ::aws/lambda-timeout-seconds 30
         ::aws/lambda-handler "awslambda.handler"
         ::aws/lambda-role-arn "arn:aws:iam::630648271175:role/service-role/onlycrayons-lambda-role-0jrh42uu"
         ::build/output-path "target/oc-lambda"}
        (ks/edn-read-string
          (slurp (expand-home "~/.aws/onlycrayons-lambda-deploy.edn")))))))

(defn test-verify-endpoint []
  (try
    (verify-endpoint
      {::aws/api-gateway-invoke-url "https://jr3ujmwd23.execute-api.us-west-2.amazonaws.com/prod/canary"})
    (catch Exception e
      (println " ! Verify failed:" e))))

(defn test-ship []
  (let [spec (merge
               {::build/output-path "target/oc-lambda"
                ::build/main-namespace "oc.node.awslambda"}
               {::aws/lambda-fn-id :onlycrayons_apiserver
                ::aws/lambda-runtime-name "nodejs12.x"
                ::aws/lambda-memory-mb 512
                ::aws/lambda-timeout-seconds 30
                ::aws/lambda-handler "awslambda.handler"
                ::aws/lambda-role-arn "arn:aws:iam::630648271175:role/service-role/onlycrayons-lambda-role-0jrh42uu"
                ::build/output-path "target/oc-lambda"}
               {::aws/api-gateway-invoke-url "https://jr3ujmwd23.execute-api.us-west-2.amazonaws.com/prod/canary"}
               (ks/edn-read-string
                 (slurp (expand-home "~/.aws/onlycrayons-lambda-deploy.edn"))))]

    (build-lambda! spec)
    (deploy-lambda! spec)
    (verify-endpoint spec)))


(comment

  (set! *print-namespace-maps* false)

  (defn deploy-lambda! [spec]
    (try
      (ks/pp
        (report-create-or-update-function spec))
      (catch Exception e
        (build/report-build-error e))
      (catch AssertionError e
        (build/report-build-error e))))

  (test-verify-endpoint)
  
  (test-ship)
  
  )
