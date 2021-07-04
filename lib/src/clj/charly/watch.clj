(ns charly.watch
  (:require [rx.kitchen-sink :as ks]
            [charly.static-templates :as st]
            [charly.config :as config]
            [charly.web :as web]
            [clojure.java.io :as io]
            [hawk.core :as hawk]
            [charly.tools-repl :as tr]
            [jansi-clj.core :as jc]
            [clojure.string :as str]
            [clojure.core.async
             :as async
             :refer [go <! timeout chan close! put!]]))

(def ! (jc/red (jc/bold "!")))
(def . (jc/red "."))
(def gbs (jc/green (jc/bold "*")))

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
                                    (config/concat-paths
                                      [project-root watch-path]))))
                 :handler (fn [ctx {:keys [kind file] :as action}]
                            (try
                              (web/write-css-out
                                (:http-root-path env)
                                out
                                (-> env :css :garden))
                              (catch Exception e
                                (println "Exception handling css compile" (pr-str action))
                                (prn e))))})))))

(defn static-dirs [{:keys [project-root dev-output-path]}]
  (let [static-path (config/concat-paths
                      [project-root "static"])]
    [{:paths [static-path]
      :handler (fn [ctx {:keys [kind file] :as action}]
                 (let [fq-static-path (config/concat-paths
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

(declare start-watchers!)

(defn handle-config-file-change [!last-env config-path]
  (let [last-env @!last-env
        next-env (config/config->env
                   (merge
                     (ks/edn-read-string (slurp config-path))
                     {:runtime-env :dev}))
        _ (reset! !last-env next-env)]
    (println gbs "Config file changed")
    (web/compile-dev next-env)
    (start-watchers! next-env)))

(defn config-file [{:keys [project-root] :as env}]
  (let [config-file-path (config/concat-paths
                           [project-root "charly.edn"])
        !last-env (atom env)]
    (when (:disable-refresh-namespaces? env)
      (println gbs "Refreshing namespaces disabled"))
    [{:paths [config-file-path]
      :handler (fn [ctx {:keys [kind file] :as action}]
                 (try
                   (handle-config-file-change !last-env (.getAbsolutePath file))
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
          (web/write-css-out
            dev-output-path
            (merge
              css-spec
              #_{:rules-fn (config/resolve-sym rules)})
            {:preamble css-preamble-fq}
            env))))))

(defn handle-routes-change [{:keys [dev-output-path project-root client-routes] :as env} nss]
  (let [nss (set nss)]
    (when (get nss (symbol (namespace client-routes)))
      (let [config-file-path (config/concat-paths
                               [project-root "charly.edn"])
            env (config/config->env
                  (merge
                    (ks/edn-read-string (slurp config-file-path))
                    {:runtime-env :dev}))]
        (web/gen-from-routes env dev-output-path)))))

(defn handle-templates-change [{:keys [dev-output-path project-root client-routes] :as env} nss]
  (let [tpl-nss (->> nss
                     (filter #(st/template-ns-sym? env %))
                     vec)]
    (when-not (empty? tpl-nss)
      (println "Tpl namespces changed: " (pr-str tpl-nss)))
    (when (> (count tpl-nss) 0)
      (st/after-templates-changed env))))

(defn handle-changed-nss [env nss]
  (handle-css-change env nss)
  (handle-routes-change env nss)
  (handle-templates-change env nss))

(defn log-error [env e]
  (let [{:keys [cause via trace]} (Throwable->map e)]
    (println . cause)
    (println
      (->> (str/split (ks/pp-str via) #"\n")
           (map #(str . " " %))
           (interpose "\n")
           (apply str)))))

(defn handle-source-files-changed [env]
  (when (not (:disable-refresh-namespaces? env))
    (tr/set-refresh-dirs "./src")
    (let [nss (tr/refresh)]
      (if (ks/error? nss)
        (do
          (println ! "Error refreshing")
          (log-error env nss))
        (when (not (empty? nss))
          (println gbs "Refreshed namespaces" nss)
          (handle-changed-nss env nss))))))

(defn source-files [{:keys [project-root debug?] :as env}]
  (let [src-path (config/concat-paths
                   [project-root "src"])]
    [{:paths [src-path]
      :filter (fn [_ {:keys [kind file]}]
                (and (.isFile file)
                     (not (.startsWith (.getName file) ".#"))))
      :handler (fn [ctx {:keys [kind file] :as action}]
                 (try
                   (handle-source-files-changed env)
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


(comment

  (start-watchers!
    (config/read-env "./charly.edn"))
  

  )

