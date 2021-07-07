(ns charly.main
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [jansi-clj.core :as j]
            [clojure.tools.cli :as tcli]
            [charly.system :as sys]))

(def cli-options
  [["-c" "--config CONFIG_PATH" "Path to charly config"]
   ["-s" "--skip-nrepl" "Skip starting nrepl server"]
   ["-d" "--dev" "Start dev"]
   ["-b" "--build" "Build prod to build/prod"]
   ["-v" "--verbose" "Print debug info to stdout"]
   ["-h" "--help" "Show this usage description"]
   ["-a" "--build-api" "Build prod api to build/api/prod"]
   ["-e" "--deploy-api" "Build and deploy prod api"]
   [nil  "--write-github-actions" "Write github actions"]
   [nil "--debug-cli" "Print cli options and exit"]
   [nil "--repl-only" "Start repl only"]
   [nil "--web-only" "Start web only"]
   [nil "--api-only" "Start api only"]])

(defn -main [& args]
  (let [{:keys [options summary errors] :as opts}
        (tcli/parse-opts args cli-options)

        {:keys [config help dev prod build
                build-api
                deploy-api
                repl-only
                debug-cli
                web-only
                api-only]} options]
    (cond
      debug-cli (do
                  (println "Provided CLI opts")
                  (ks/pp opts)
                  (System/exit 1))
      help (do
             (println "Charly CLI")
             (println summary)
             (System/exit 1))

      repl-only (sys/start-repl-only! options)
      dev (sys/start-dev! options)
      build (do (sys/build-prod-web! options)
                (System/exit 0))
      build-api (do (sys/build-prod-api! options)
                    (System/exit 0))

      deploy-api (do (sys/deploy-prod-api! options)
                     (System/exit 0))

      web-only (sys/start-web-dev! options)

      api-only (sys/start-node-dev! options)
      errors
      (do
        (doseq [s errors]
          (println (j/red (j/bold s))))
        (System/exit 1))

      (:write-github-actions options)
      (sys/cmd-write-github-actions options)
      
      :else (do (println "Please provide one of --dev or --build, or -h for help")
                (System/exit 1)))))




