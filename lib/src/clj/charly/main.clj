(ns charly.main
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [charly.config :as config]
            [charly.compiler :as cmp]
            [charly.watch :as watch]
            [charly.cicd :as cicd]
            [charly.cli :as cli]
            [figwheel.main.api :as fapi]
            [clojure.tools.cli :as tcli]
            [charly.tools-repl :as tr]
            [jansi-clj.core :refer [red bold]]))

(defn options-read-config [options]
  (cli/read-config (or (:config options) "./charly.edn")))

(defn cmd-write-github-actions [{:keys [config verbose] :as options}]
  (println "* Writing github actions to repo")
  (let [env (merge
              {:debug? verbose}
              (-> options
                  options-read-config
                  config/config->env))]
    (cicd/spit-github-actions env)))

(defn start-node-dev? [env]
  (:api-cljs env))

(defn start-dev! [& [{config-path :config
                      :keys [skip-nrepl verbose]}]]
  (let [config (merge
                 (cli/read-config (or config-path "./charly.edn"))
                 {:runtime-env :dev
                  :debug? verbose})
        {:keys [tools-repl-refresh-dirs]} config]
    (apply tr/set-refresh-dirs tools-repl-refresh-dirs)
    (let [res (tr/refresh)]
      (when (ks/error? res)
        (println "Error on initial refresh")
        (ks/pp res)))
    (when-not (anom/? config)
      (let [env (config/config->env config)]
        (when verbose
          (ks/spy "ENV" env))
        (cli/compile-dev env)
        (watch/start-watchers! env)
        (cli/start-http-server! env)
        (cli/start-figwheel-server! env)
        (when (start-node-dev? env)
          (cli/start-node-dev! env))
        (when-not skip-nrepl
          (cli/start-nrepl-server! {}))))))

(defn build-prod! [& [{config-path :config :keys [verbose]}]]
  (println "\n")
  (println "Generating production build")
  (tr/set-refresh-dirs "./src")
  (let [res (tr/refresh)]
    (when (ks/error? res)
      (println "Error on initial refresh")
      (ks/pp res)
      (System/exit 1)))
  (let [config (merge
                 (cli/read-config (or config-path "./charly.edn"))
                 {:runtime-env :prod})]
    (when-not (anom/? config)
      (let [env (config/config->env config)]
        (when verbose
          (ks/spy "ENV" env))
        (cli/compile-prod env))))
  (println "*** Done generating production build"))

(defn build-prod-api! [& [{config-path :config :keys [verbose]}]]
  (println "\n")
  (println "Generating production API build")
  (tr/set-refresh-dirs "./src")
  (let [res (tr/refresh)]
    (when (ks/error? res)
      (println "Error on initial refresh")
      (ks/pp res)
      (System/exit 1)))
  (let [config (merge
                 (cli/read-config (or config-path "./charly.edn"))
                 {:runtime-env :dev})]
    (when-not (anom/? config)
      (let [env (config/config->env config)]
        (when verbose
          (ks/spy "ENV" env))
        (cli/compile-prod-api env))))
  (println "*** Done generating production build"))

(defn deploy-prod-api! [& [{config-path :config :keys [verbose]}]]
  (println "\n")
  (println "Deploying production API build")
  (tr/set-refresh-dirs "./src")
  (tr/refresh)
  (let [config (merge
                 (cli/read-config (or config-path "./charly.edn"))
                 {:runtime-env :dev})]
    (when-not (anom/? config)
      (let [env (config/config->env config)]
        (when verbose
          (ks/spy "ENV" env))
        (cli/deploy-prod-api env))))
  (println "*** Done deploying API"))

(defn web-repl []
  (fapi/cljs-repl "charly-cljs"))

(defn api-repl []
  (fapi/cljs-repl "charly-api-cljs"))

(defn load-env! [& [{config-path :config
                      :keys [verbose]}]]
  (let [config (merge
                 (cli/read-config (or config-path "./charly.edn"))
                 {:runtime-env :dev
                  :debug? verbose})]
    (if (anom/? config)
      config
      (config/config->env config))))

(defn restart-api! [& [opts]]
  (let [env (load-env! opts)]

    (anom/throw-if-anom env)

    (when (start-node-dev? env)
      (cli/start-node-dev! env))))

(comment

  (restart-api!)

  )

(def cli-options
  [["-c" "--config CONFIG_PATH" "Path to charly config"]
   ["-s" "--skip-nrepl" "Skip starting nrepl server"]
   ["-d" "--dev" "Start dev"]
   ["-b" "--build" "Build prod to build/prod"]
   ["-v" "--verbose" "Print debug info to stdout"]
   ["-h" "--help" "Show this usage description"]
   ["-a" "--build-api" "Build prod api to build/api/prod"]
   ["-e" "--deploy-api" "Build and deploy prod api"]
   [nil  "--write-github-actions" "Write github actions"]])

(defn -main [& args]
  (let [{:keys [options summary errors] :as opts}
        (tcli/parse-opts args cli-options)

        {:keys [config help dev prod build
                build-api
                deploy-api]} options]
    (cond
      help (do
             (println "Charly CLI")
             (println summary)
             (System/exit 1))
      dev (start-dev! options)
      build (do (build-prod! options)
                (System/exit 0))
      build-api (do (build-prod-api! options)
                    (System/exit 0))

      deploy-api (do (deploy-prod-api! options)
                     (System/exit 0))
      errors
      (do
        (doseq [s errors]
          (println (red (bold s))))
        (System/exit 1))

      (:write-github-actions options)
      (cmd-write-github-actions options)
      
      :else (do (println "Please provide one of --dev or --build, or -h for help")
                (System/exit 1)))))


(comment

  (-main "-c" "./charly.edn")
  (-main "-h")
  (-main "--asdfasdf")

  (-main "--dev" "--skip-nrepl" "--verbose")

  (-main "--build")

  (-main "-e")

  (-main "--write-github-actions" "--verbose")

  (cljs-repl)

  (cljs-api-repl)

  (web-repl)

  (start-dev! {:config-path "charly.edn"})

  (build-prod! {:config-path "charly.edn"})

  (start! {:config-path "./resources/charly/test_site/charly_error.edn"})

  (ks/spy (config/config->env (merge
                                (read-config "./resources/charly/test_site/charly.edn")
                                {:runtime-env :dev})))
  (read-config "./resources/charly/test_site/charly_error.edn"))
