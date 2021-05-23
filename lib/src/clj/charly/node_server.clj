(ns charly.node-server
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [rx.jvm.ops.build :as build]
            [charly.config :as config]
            [me.raynes.conch.low-level :as cl]
            [me.raynes.conch :as conch]
            [clojure.java.io :as io]
            [figwheel.main.api :as fapi]
            [cljs.build.api :as bapi]
            [charly.aws-lambda-deploy :as ld]
            [clojure.string :as str]))

(defonce !servers (atom nil))

(defn opts->output-dir [{:keys [project-root id]}]
  (config/concat-paths
    [project-root "build/api/dev"]))

(defn opts->output-to [env]
  (config/concat-paths
    [(opts->output-dir env) "app.js"]))

(defn opts->node-opts [opts]
  (let [max-old-space-size
        (or (-> opts :node-proc :max-old-space-size)
            4096)
        js-path (opts->output-to opts)]
    (when-not js-path
      (anom/throw-anom
        {:desc "Missing js-path"
         :var #'opts->node-opts}))
    {:command ["node"
               (str "--max_old_space_size=" max-old-space-size)
               js-path]}))

(defn node-proc? [opts]
  (:node-proc opts))

(defn stop-node-proc! [{:keys [id]}]
  (let [id "cljs-api-repl"])
    (when-let [proc (get-in @!servers [id :node-proc])]
      (ks/pr "Destorying node proc... ")
      (cl/destroy proc)
      (ks/pn "Done.")
      (swap! !servers assoc-in [id :node-proc] nil)))

(defn start-node-proc! [{:keys [id] :as opts}]
  (let [id "cljs-api-repl"
        {:keys [command]} (opts->node-opts opts)]
    (when-not command
      (anom/throw-anom
        {:desc "Node command missing"
         :var #'start-node-proc!}))
    (stop-node-proc! opts)
    (ks/pr "Starting node proc... ")
    (swap! !servers
      assoc-in
      [id :node-proc]
      (let [proc (apply cl/proc command)]
        (future (cl/stream-to-out proc :out))
        (future (cl/stream-to-out proc :err))
        proc))
    (ks/pn "Done.")))


;; Figwheel

(defn stop-figwheel-server! [{:keys [id]}]
  (let [id "charly-api-cljs"]
    (try
      (fapi/stop id)
      (catch Exception e
        #_(ks/pn "Server already stopped")))))

(defn watch-dirs [{:keys [project-root]}]
  (->> [["src" "cljs-node"]
        ["src" "cljc"]]
       (map (fn [parts]
              (config/concat-paths
                (concat
                  [project-root]
                  parts))))))

(defn figwheel-opts [{:keys [client-cljs opts project-root] :as env}]
  (let [watch-dirs (watch-dirs env)]
    (ks/deep-merge
      {:mode :serve
       :open-url false
       :ring-server-options {:port 5051}
       :watch-dirs watch-dirs
       :validate-config true
       :rebel-readline false
       :launch-node false
       :hot-reload-cljs true}
      (:figwheel client-cljs))))

(defn figwheel-compiler-opts [{:keys [api-cljs api-dev-output-path] :as opts}]
  (assert api-dev-output-path)
  (let [{:keys [dev-main]} api-cljs]
    (ks/deep-merge
      {:output-to (config/concat-paths
                    [api-dev-output-path "app.js"])
       :output-dir (config/concat-paths
                     [api-dev-output-path])
       :warnings {:single-segment-namespace false}
       :closure-warnings {:externs-validation :off}
       :target :nodejs
       :optimizations :none
       :source-map true
       :parallel-build true
       :main dev-main}
      (:compiler api-cljs))))

(defn compile-prod-cljs [{:keys [api-prod-output-path project-root api-cljs]
                          :as env}
                         & [{:keys [verbose?]}]]
  (let [cljs-build-dir (config/concat-paths
                         [project-root "build" "api" "prod-cljs"])

        {:keys [prod-main]} api-cljs

        compiler-opts (ks/deep-merge
                        {:output-to (config/concat-paths
                                      [cljs-build-dir "app.js"])
                         :output-dir cljs-build-dir
                         :target :nodejs
                         :optimizations :simple
                         :warnings {:single-segment-namespace false}
                         :closure-warnings {:externs-validation :off}
                         :source-map (config/concat-paths
                                       [cljs-build-dir "app.js.map"])
                         :main prod-main
                         :parallel-build true}
                        (:compiler api-cljs))]
    (println "Compiling cljs...")
    
    (when verbose?
      (println "")
      (println "inputs")
      (ks/pp (watch-dirs env))
      (println "")
      (println "compiler")
      (ks/pp compiler-opts))
    (bapi/build
      (apply bapi/inputs (watch-dirs env))
      compiler-opts)

    (io/make-parents
      (io/as-file
        (config/concat-paths
          [api-prod-output-path "app.js"])))

    (let [from-path (config/concat-paths
                      [cljs-build-dir "app.js"])
          to-path (config/concat-paths
                    [api-prod-output-path "app.js"])]
      (when verbose?
        (println)
        (println "Copying" from-path "to" to-path))
      (io/copy (io/as-file from-path) (io/as-file to-path)))

    (let [from-path (config/concat-paths
                      [cljs-build-dir "app.js.map"])
          to-path (config/concat-paths
                    [cljs-build-dir "app.js.map"])]
      (when verbose?
        (println "Copying" from-path "to" to-path))
      (io/copy (io/as-file from-path) (io/as-file to-path)))))


(defn copy-node-modules [{:keys [api-prod-output-path project-root]}
                         & [{:keys [verbose?]}]]
  (let [package-json (ks/from-json
                       (slurp (config/concat-paths
                                [project-root "./package.json"]))
                       true)
        deps (get package-json "dependencies")
        lib-paths (->> deps
                       (map (fn [[s _]]
                              (config/concat-paths
                                [project-root "node_modules"
                                 (first (str/split s #"/"))]))))]
    (println "* Copying node_modules")
    (build/mkdir
      (config/concat-paths
        [api-prod-output-path "node_modules"]))
    (doseq [path lib-paths]
      (build/report-and-run
        ["cp" "-R" path (config/concat-paths
                          [api-prod-output-path "node_modules"])]))))

(defn install-node-modules [{:keys [api-prod-output-path project-root]}
                            & [{:keys [verbose?]}]]
  (println "* Copying node_modules")
  (build/report-and-run
    ["cp" "-R"
     "./node_modules"
     api-prod-output-path]))

(defn create-zip [{:keys [api-prod-output-path]}
                  & [{:keys [verbose?]}]]
  (let [zip-path (.getCanonicalPath
                   (io/as-file
                     (config/concat-paths
                       [api-prod-output-path "../awslambda.zip"])))]
    (println "Creating" zip-path)
    (build/zip
      api-prod-output-path
      zip-path)))

(defn package-prod-cljs [env & [opts]]
  #_(copy-node-modules env opts)
  (install-node-modules env opts)
  (create-zip env opts))

(defn start-figwheel-server! [{:keys [client-cljs] :as opts}]
  (let [id "charly-api-cljs"]
    (stop-figwheel-server! client-cljs)
    (fapi/start
      (figwheel-opts opts)
      {:id id
       :options (figwheel-compiler-opts opts)})))

(defn compile-prod-api [env & [opts]]
  (compile-prod-cljs env opts)
  (package-prod-cljs env opts))

(defn deploy-prod-api [env & [opts]]
  (println "Deploying lambda api")
  (compile-prod-cljs env opts)
  #_(copy-node-modules env opts)
  (install-node-modules env opts)
  (create-zip env opts)
  (ld/deploy! env opts))

(comment

  (deploy-prod-api
    (config/read-env "./charly.edn")
    {:verbose? true})

  (compile-prod-cljs
    (config/read-env "./charly.edn")
    {:verbose? true})

  (copy-node-modules
    (config/read-env "./charly.edn")
    {:verbose? true})

  (install-node-modules
    (config/read-env "./charly.edn")
    {:verbose? true})

  (create-zip
    (config/read-env "./charly.edn")
    {:verbose? true})

  )



