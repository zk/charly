(ns charly.nrepl-server
  (:require [rx.kitchen-sink :as ks]
            [nrepl.cmdline :as nrepl]
            [nrepl.server :as nrepl-server]))

(defonce !nrepl-server (atom nil))

(defn start-clj-repl [env]
  (let [nrepl-opts (nrepl/server-opts
                     {:middleware
                      '[cider.nrepl/cider-middleware
                        cider.piggieback/wrap-cljs-repl]})]
    (when @!nrepl-server
      (nrepl-server/stop-server @!nrepl-server))


    (let [server (nrepl/start-server nrepl-opts)]
      (reset! !nrepl-server server)
      (println (nrepl/server-started-message server nrepl-opts))
      (nrepl/save-port-file server nrepl-opts))))
