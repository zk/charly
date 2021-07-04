(ns charly.web
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [charly.config :as cfg]
            [charly.static-templates :as st]
            [charly.nrepl-server :as nr]
            [charly.http-server :as hs]
            [jansi-clj.core :as j]
            [charly.node-server :as ns]
            [figwheel.main.evalback :as evalback]
            [figwheel.main.api :as fapi]
            [clojure.java.io :as io]
            [garden.core :as gc]
            [cljs.build.api :as bapi]
            [clojure.string :as str]))

(def ! (j/red (j/bold "!")))
(def gbs (j/green (j/bold "*")))
(def bold j/bold)
(def red j/red)

(defn write-css-out [output-to
                     {:keys [path rules-fn rules-var rules]}
                     garden-opts
                     env]
  (let [output-path (cfg/concat-paths [output-to "css" path])]
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

(defn gen-css [env output-path minify?]
  (println gbs "Generating css")
  (let [paths (generate-css env output-path minify?)]
    (doseq [path paths]
      (println " " path))))

(defn gen-from-routes [env output-path]
  (println gbs "Generating html files from routes")
  (let [{:keys [routes-fn client-routes]} env]
    (if (anom/? routes-fn)
      (println ! "Couldn't resolve routes fn" client-routes routes-fn)
      (let [routes-res (st/generate-routes env output-path)]
        (if (anom/? routes-res)
          (do
            (println ! "Error generating route html")
            (println "  " routes-res))
          (doseq [{:keys [output-path]} routes-res]
            (println " " output-path)))))))

(defn compile-dev [env]
  (let [{:keys [dev-output-path]} env]
    (println gbs "Copying static files")
    (let [copy-res (st/copy-static env dev-output-path)]
      (doseq [{:keys [to-file]} copy-res]
        (println " " (.getPath to-file)))
      (gen-from-routes env dev-output-path)
      (gen-css env dev-output-path false))))


(defn compile-prod-css [{:keys [prod-target-dir]
                         css-spec :css}]
  (doseq [out (:outs css-spec)]
    (write-css-out
      prod-target-dir
      out
      (:garden css-spec))))

(defn watch-dirs [{:keys [project-root]}]
  (->> [["src" "cljs-browser"]
        ["src" "cljc"]]
       (map (fn [parts]
              (cfg/concat-paths
                (concat
                  [project-root]
                  parts))))))

(defn compile-prod-cljs [{:keys [prod-output-path project-root client-cljs]
                          :as env}]
  (let [cljs-build-dir (cfg/concat-paths
                         [project-root "build" "prod-cljs"])]
    (println "Compiling cljs...")
    (bapi/build
      (apply bapi/inputs (watch-dirs env))
      (ks/deep-merge
        {:output-to (cfg/concat-paths
                      [cljs-build-dir "app.js"])
         :output-dir cljs-build-dir
         :optimizations :advanced
         :warnings {:single-segment-namespace false}
         :closure-warnings {:externs-validation :off}
         :source-map (cfg/concat-paths
                       [cljs-build-dir "app.js.map"])
         :parallel-build true
         :asset-path "/cljs"}
        (:compiler client-cljs)))

    (io/make-parents
      (io/as-file
        (cfg/concat-paths
          [prod-output-path "cljs" "app.js"])))

    (io/copy
      (io/as-file
        (cfg/concat-paths
          [cljs-build-dir "app.js"]))
      (io/as-file
        (cfg/concat-paths
          [prod-output-path "cljs" "app.js"])))

    (io/copy
      (io/as-file
        (cfg/concat-paths
          [cljs-build-dir "app.js.map"]))
      (io/as-file
        (cfg/concat-paths
          [cljs-build-dir "app.js.map"])))))

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
      (cfg/concat-paths
        [prod-output-path "vercel.json"])
      json-str)))
