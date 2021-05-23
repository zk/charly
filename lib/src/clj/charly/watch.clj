(ns charly.watch
  (:require [rx.kitchen-sink :as ks]
            [charly.compiler :as c]
            [charly.static-templates :as st]
            [charly.config :as config]
            [charly.cli :as cli]
            [clojure.java.io :as io]
            [hawk.core :as hawk]
            [charly.tools-repl :as tr]
            [clojure.core.async
             :as async
             :refer [go <! timeout chan close! put!]]))

(defn create-directory [path]
  (.mkdirs (io/file path)))

(defn css-watchers [env]
  (let [{:keys [project-root]} env]
    (->> env
         :css
         :outs
         (map (fn [{:keys [watch-paths] :as out}]
                {:paths (->> watch-paths
                             (map (fn [watch-path]
                                    (c/concat-paths
                                      [project-root watch-path]))))
                 :handler (fn [ctx {:keys [kind file] :as action}]
                            (try
                              (c/write-css-out
                                (:http-root-path env)
                                out
                                (-> env :css :garden))
                              (catch Exception e
                                (println "Exception handling css compile" (pr-str action))
                                (prn e))))})))))

(defn static-dirs [{:keys [project-root dev-output-path]}]
  (let [static-path (c/concat-paths
                      [project-root "static"])]
    [{:paths [static-path]
      :handler (fn [ctx {:keys [kind file] :as action}]
                 (let [fq-static-path (c/concat-paths
                                        [(System/getProperty "user.dir") static-path])
                       to-file (st/to-file
                                 file
                                 fq-static-path
                                 dev-output-path)]
                   (when (or (.isFile file) (= :delete kind))
                     (let [copy-spec {:from-file file
                                      :to-file to-file}]
                       (try
                         (if (get #{:create :modify} kind)
                           (println "File changed, updating" (.getPath to-file))
                           (println "File deleted, removing" (.getPath to-file)))
                         (condp = kind
                           :create (st/copy-static-file copy-spec)
                           :modify (st/copy-static-file copy-spec)
                           :delete (io/delete-file (:to-file copy-spec)))
                         (catch Exception e
                           (println "Exception handling filesystem change" (pr-str action))
                           (prn e)))))))}]))

(defn config-file [{:keys [project-root] :as env}]
  (let [config-file-path (c/concat-paths
                           [project-root "charly.edn"])
        !last-env (atom env)]
    [{:paths [config-file-path]
      :handler (fn [ctx {:keys [kind file] :as action}]
                 (try
                   (let [last-env @!last-env
                         next-env (config/config->env
                                    (merge
                                      (ks/edn-read-string (slurp file))
                                      {:runtime-env :dev}))
                         _ (reset! !last-env next-env)]
                     (cli/compile-dev next-env))
                   (catch Exception e
                     (println "Exception handling filesystem change" (pr-str action))
                     (prn e))))}]))

(defn handle-css-change [{:keys [css-preamble-fq dev-output-path debug?] :as env} nss]
  (when debug?
    (println "Handling css namespaces change:" (pr-str nss)))
  (let [nss (set nss)]
    (doseq [{:keys [rules-ns-sym rules] :as css-spec} (:css-files env)]
      (when (get nss rules-ns-sym)
        (println "CSS changed, writing"
          (c/write-css-out
            dev-output-path
            (merge
              css-spec
              #_{:rules-fn (config/resolve-sym rules)})
            {:preamble css-preamble-fq}
            env))))))

(defn handle-routes-change [{:keys [dev-output-path project-root client-routes] :as env} nss]
  (let [nss (set nss)]
    (when (get nss (symbol (namespace client-routes)))
      (let [config-file-path (c/concat-paths
                               [project-root "charly.edn"])
            env (config/config->env
                  (merge
                    (ks/edn-read-string (slurp config-file-path))
                    {:runtime-env :dev}))]
        (cli/gen-from-routes env dev-output-path)))))

(defn handle-changed-nss [env nss]
  (handle-css-change env nss)
  (handle-routes-change env nss))

(defn source-files [{:keys [project-root debug?] :as env}]
  (let [src-path (c/concat-paths
                   [project-root "src"])]
    [{:paths [src-path]
      :filter (fn [_ {:keys [kind file]}]
                (and (.isFile file)
                     (not (.startsWith (.getName file) ".#"))))
      :handler (fn [ctx {:keys [kind file] :as action}]
                 (try
                   (tr/set-refresh-dirs "./src")
                   (let [nss (tr/refresh)]
                     (handle-changed-nss env nss))
                   (catch Exception e
                     (println "Exception handling filesystem change" (pr-str action))
                     (prn e))))}]))

(defn start-watchers [env]
  (let [ch (chan)]
    (hawk/watch!
      {:watcher :polling}
      (concat
        (static-dirs env)
        (config-file env)
        (source-files env)))))

(defonce !watcher (atom nil))

(defn start-watchers! [env]
  (when @!watcher
    (hawk/stop! @!watcher))

  (reset! !watcher
    (start-watchers env)))
