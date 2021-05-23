(ns charly.compiler
  (:require
   [rx.kitchen-sink :as ks]
   [reitit.core :as rei]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.java.shell :as sh]
   [figwheel.main.api :as fapi]
   [garden.core :as gc]
   [cljs.build.api :as bapi]
   [rum.server-render :as sr]
   [charly.config :as cfg]
   [charly.static-templates :as st])
  (:refer-clojure :exclude [compile]))

(defn concat-paths [parts]
  (str/replace
    (->> parts
         (remove nil?)
         (interpose "/")
         (apply str))
    #"/+" "/"))

;; CSS

(defn write-css-out [output-to
                     {:keys [path rules-fn rules-var rules]}
                     garden-opts
                     env]
  (let [output-path (concat-paths [output-to "css" path])]
    (io/make-parents (io/as-file output-path))
    (spit
      output-path
      (gc/css
        (ks/deep-merge
          {:pretty-print? true
           :vendors ["webkit" "moz" "ms"]
           :auto-prefix #{:justify-content
                          :align-items
                          :flex-direction
                          :flex-wrap
                          :align-self
                          :transition
                          :transform
                          :background-clip
                          :background-origin
                          :background-size
                          :filter
                          :font-feature-settings
                          :appearance}}
          garden-opts)
        ((cfg/resolve-var rules) env)))
    output-path))

(defn compile-css [{:keys [http-root-path]
                    css-spec :css}]
  (doseq [out (:outs css-spec)]
    (write-css-out
      http-root-path
      out
      (:garden css-spec))))

(defn compile-prod-css [{:keys [prod-target-dir]
                         css-spec :css}]
  (doseq [out (:outs css-spec)]
    (write-css-out
      prod-target-dir
      out
      (:garden css-spec))))

;; ClojureScript

(defn stop-figwheel-server! [{:keys [id]}]
  (let [id (or id "charly-cljs")
        id "charly-cljs"]
    (try
      (fapi/stop id)
      (catch Exception e
        #_(ks/pn "Server already stopped")))))

(defn watch-dirs [{:keys [project-root]}]
  (->> [["src" "cljs-browser"]
        ["src" "cljc"]]
       (map (fn [parts]
              (concat-paths
                (concat
                  [project-root]
                  parts))))))

(defn figwheel-opts [{:keys [client-cljs opts project-root dev-output-path] :as env}]
  (let [watch-dirs (watch-dirs env)]
    (ks/deep-merge
      {:mode :serve
       :open-url false
       :ring-server-options {:port 5001}
       :watch-dirs watch-dirs
       :validate-config true
       :rebel-readline false
       :launch-node false
       :hot-reload-cljs true
       :css-dirs [(concat-paths [dev-output-path "css"])]}
      (:figwheel client-cljs))))

(defn figwheel-compiler-opts [{:keys [client-cljs dev-output-path] :as opts}]
  (ks/deep-merge
    {:output-to (concat-paths
                  [dev-output-path "cljs" "app.js"])
     :output-dir (concat-paths
                  [dev-output-path "cljs"])
     :warnings {:single-segment-namespace false}
     :closure-warnings {:externs-validation :off}
     :optimizations :none
     :source-map true
     :parallel-build true
     :asset-path "/cljs"}
    (:compiler client-cljs)))

(defn compile-prod-cljs [{:keys [prod-output-path project-root client-cljs]
                          :as env}]
  (let [cljs-build-dir (concat-paths
                         [project-root "build" "prod-cljs"])]
    (println "Compiling cljs...")
    (bapi/build
      (apply bapi/inputs (watch-dirs env))
      (ks/deep-merge
        {:output-to (concat-paths
                      [cljs-build-dir "app.js"])
         :output-dir cljs-build-dir
         :optimizations :advanced
         :warnings {:single-segment-namespace false}
         :closure-warnings {:externs-validation :off}
         :source-map (concat-paths
                       [cljs-build-dir "app.js.map"])
         :parallel-build true
         :asset-path "/cljs"}
        (:compiler client-cljs)))

    (io/make-parents
      (io/as-file
        (concat-paths
          [prod-output-path "cljs" "app.js"])))

    (io/copy
      (io/as-file
        (concat-paths
          [cljs-build-dir "app.js"]))
      (io/as-file
        (concat-paths
          [prod-output-path "cljs" "app.js"])))

    (io/copy
      (io/as-file
        (concat-paths
          [cljs-build-dir "app.js.map"]))
      (io/as-file
        (concat-paths
          [cljs-build-dir "app.js.map"])))))

(defn start-figwheel-server! [{:keys [client-cljs id] :as opts}]
  (let [id (or id "charly-cljs")
        id "charly-cljs"]
    (stop-figwheel-server! client-cljs)
    (fapi/start
      (figwheel-opts opts)
      {:id id
       :options (figwheel-compiler-opts opts)})))

(defn generate-css [{:keys [css-preamble-fq css-files] :as env} output-to minify?]
  (->> css-files
       (map (fn [out]
              (write-css-out
                output-to
                out
                {:preamble css-preamble-fq
                 :pretty-print? (not minify?)}
                env)))
       doall))

(defn generate-vercel-json [{:keys [routes-fn prod-output-path] :as env}]
  (let [routes (routes-fn env)
        json-str (-> {:cleanUrls true
                      :rewrites (->> routes
                                     (filter #(str/includes? (first %) ":"))
                                     (mapv
                                       (fn [[path _]]
                                         {:source path
                                          :destination (str/replace path #":" "__cln__")})))}
                     ks/to-json)]
    (spit
      (concat-paths
        [prod-output-path "vercel.json"])
      json-str)))

(defn compile
  "Takes an environment map and outputs a charly site to the target directory"
  [env output-path]
  (st/copy-static env output-path)
  #_(gen-files env)
  #_(compile-css env))

(defn compile-prod [env]
  (st/copy-prod-directories env)
  (st/copy-prod-files env)
  (st/gen-prod-files env)
  (compile-prod-css env)
  (compile-prod-cljs env)
  (println "Done compiling"))

