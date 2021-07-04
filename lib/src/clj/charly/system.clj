(ns charly.system
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [charly.config :as cfg]
            [charly.nrepl-server :as nr]
            [charly.node-server :as ns]
            [charly.web :as web]
            [jansi-clj.core :as j]
            [charly.tools-repl :as tr]
            [charly.watch :as watch]
            [charly.http-server :as hs]
            [charly.static-templates :as st]
            [figwheel.main.api :as fapi]
            [figwheel.main :as fmain]
            [charly.cicd :as cicd]))

(def gbs (j/green (j/bold "*")))

(defn start-repl-only! [opts]
  (println gbs "Starting nrepl server")
  (nr/start-clj-repl opts))

(defn start-nrepl-server [opts]
  (println gbs "Starting nrepl server")
  (nr/start-clj-repl opts))

(defn init-source-refresh [config]
  (let [{:keys [tools-repl-refresh-dirs]} config]
    (apply tr/set-refresh-dirs tools-repl-refresh-dirs)
    (let [res (tr/refresh)]
      (when (ks/error? res)
        (println "Error on initial refresh")
        (ks/pp res)))))

(defn load-env [opts]
  (let [path (or (:config opts) "./charly.edn")
        config (cfg/read-config path)]
    (init-source-refresh config)
    (if (anom/? config)
      (do
        (println (j/red (j/bold "[!]")) (str "Error parsing config file: " path))
        (println "   " (::anom/desc config))
        config)
      (let [env (cfg/config->env config)]
        (when (anom/? env)
          (println (j/red (j/bold "[!]")) (str "Error creating env"))
          (println "   " (::anom/desc env)))
        (merge
          env
          {:debug? (:vebose opts)})))))

(defn load-dev-env [opts]
  (merge
    (load-env opts)
    {:runtime-env :dev}))

(defn load-prod-env [opts]
  (merge
    (load-env opts)
    {:runtime-env :prod}))

(defn start-http-server [env]
  (println gbs "Starting dev server")
  (hs/start-http-server! env))

(defn watch-dirs [{:keys [project-root]}]
  (->> [["src" "cljs-browser"]
        ["src" "cljc"]]
       (map (fn [parts]
              (cfg/concat-paths
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
       :css-dirs [(cfg/concat-paths [dev-output-path "css"])]}
      (:figwheel client-cljs))))

(defn figwheel-compiler-opts [{:keys [client-cljs dev-output-path] :as opts}]
  (ks/deep-merge
    {:output-to (cfg/concat-paths
                  [dev-output-path "cljs" "app.js"])
     :output-dir (cfg/concat-paths
                  [dev-output-path "cljs"])
     :warnings {:single-segment-namespace false}
     :closure-warnings {:externs-validation :off}
     :optimizations :none
     :source-map true
     :parallel-build true
     :asset-path "/cljs"}
    (:compiler client-cljs)))

(defn stop-figwheel-server [{:keys [id]}]
  (let [id (or id "charly-cljs")
        id "charly-cljs"]
    (try
      (fapi/stop id)
      (catch Exception e
        (prn "Server already stopped")))))

(defn start-figwheel-server [{:keys [client-cljs id] :as opts}]
  (let [id (or id "charly-cljs")
        id "charly-cljs"]
    (stop-figwheel-server client-cljs)
    (try
      (fapi/start
        (figwheel-opts opts)
        {:id id
         :options (figwheel-compiler-opts opts)})
      (catch Exception e
        (fmain/start-builds id)))))

(defn start-node-dev? [env]
  (:api-cljs env))

(defn start-node-dev [env]
  (println gbs "Start node proc")
  (ns/start-figwheel-server! env)
  (ns/start-node-proc! env))

(defn start-dev [{:keys [debug?] :as env}]
  (when debug?
    (ks/spy "ENV" env))
  (web/compile-dev env)
  (watch/start-watchers! env)
  (start-http-server env)
  (start-figwheel-server env)
  (when (start-node-dev? env)
    (start-node-dev env)))

(defn start-dev! [{:keys [skip-nrepl] :as opts}]
  (let [env (load-dev-env opts)]
    (when-not (anom/? env)
      (start-dev env)
      (when-not skip-nrepl
        (start-nrepl-server {})))))

(defn compile-prod-web [env]
  (let [{:keys [prod-output-path]} env]
    (println gbs "Copying static files")
    (let [copy-res (st/copy-static env prod-output-path)]
      (doseq [{:keys [to-file]} copy-res]
        (println "  Wrote" (.getPath to-file)))
      (web/gen-from-routes env prod-output-path)
      (web/gen-css env prod-output-path false)
      (web/compile-prod-cljs env)
      (web/generate-vercel-json env))))

(defn build-prod-web [{:keys [debug?] :as env}]
  (when debug?
    (ks/spy "ENV" env))
  (compile-prod-web env))

(defn build-prod-web! [opts]
  (println "\n")
  (println "Generating production build")
  (let [env (load-prod-env opts)]
    (when-not (anom/? env)
      (build-prod-web env)
      (println "*** Done generating production build"))))

(defn compile-prod-api [{:keys [debug?] :as env}]
  (when debug?
    (ks/spy "ENV" env))
  (let [{:keys [prod-output-path]} env]
    (println gbs "Building prod api")
    (ns/compile-prod-api env)))

(defn build-prod-api! [opts]
  (println "\n")
  (println "Generating production API build")
  (let [env (load-prod-env opts)]
    (when-not (anom/? env)
      (compile-prod-api env)
      (println "*** Done generating production build"))))

(defn deploy-prod-api! [opts]
  (println "\n")
  (println "Deploying production API build")
  (let [env (load-prod-env opts)]
    (when-not (anom/? env)
      (when (:debug? env)
        (ks/spy "ENV" env))
      (let [{:keys [prod-output-path]} env]
        (println gbs "Deploying prod api")
        (ns/deploy-prod-api env))
      (println "*** Done deploying API"))))

(defn cmd-write-github-actions [opts]
  (println "* Writing github actions to repo")
  (let [env (load-prod-env opts)]
    (cicd/spit-github-actions env)))

(defn web-repl []
  (fapi/cljs-repl "charly-cljs"))

(defn api-repl []
  (fapi/cljs-repl "charly-api-cljs"))

(defn restart-api! [& [opts]]
  (let [env (load-dev-env opts)]

    (anom/throw-if-anom env)

    (when (start-node-dev? env)
      (start-node-dev env))))

(comment

  (start-dev (load-dev-env {}))

  (start-http-server (load-dev-env {}))

  (start-figwheel-server (load-dev-env {}))

  (build-prod-web! {})

  (build-prod-api! {})

  (deploy-prod-api! {})

  (cmd-write-github-actions {})

  (restart-api! {})

  ;; Repl API

  )


