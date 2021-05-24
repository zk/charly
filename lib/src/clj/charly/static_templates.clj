(ns charly.static-templates
  "Behavior around statically generated html templates"
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [charly.config :as c]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [reitit.core :as rei]
            [rum.server-render :as sr]
            [figwheel.repl :as fr]))

(defn env->tpl-config [env]
  (let [{:keys [id]} env]))

(defn create-directory [path]
  (.mkdirs (io/file path)))

(defn copy-dir [{:keys [src dst]} target-dir]
  (let [dst-dir (c/concat-paths [target-dir dst])
        files (->> src
                   io/as-file
                   file-seq
                   (filter #(not (.isDirectory %)))
                   (filter #(.exists %)))]
    (println "Copying" src "to" dst-dir)
    (create-directory dst-dir)
    (doseq [file files]
      (let [to-file (io/as-file
                      (c/concat-paths
                        [dst-dir (.getName file)]))]
        (io/make-parents to-file)
        (io/copy file to-file)
        (println " " (.getPath to-file))))))

(defn copy-dirs [env to-dir]
  (let [{:keys [copy-dirs]} env]
    (doseq [dir copy-dirs]
      (try
        (copy-dir dir to-dir)
        (catch Exception e
          (println "Error copying directory" dir)
          (prn e))))))

(defn copy-directories [env]
  (let [{:keys [target-dir]} env]
    (copy-dirs env target-dir)))

(defn copy-prod-directories [env]
  (let [{:keys [prod-target-dir]} env]
    (copy-dirs env prod-target-dir)))

(defn copy-file [{:keys [src dst]} target-dir]
  (let [src-file (io/as-file src)
        dst-path (c/concat-paths [target-dir dst])
        dst-file (io/as-file dst-path)]
    (try
      (io/make-parents dst-file)
      (io/copy src-file dst-file)
      (println "Copying" src "to" (.getPath dst-file))
      (catch Exception e
        (println "! Error copying file. SOURCE"
          (.getPath src-file)
          "DEST"
          (.getPath dst-file))
        (throw e)))))

(defn copy-files [env]
  (let [{:keys [target-dir copy-files]} env]
    (doseq [file copy-files]
      (copy-file file target-dir))))

(defn copy-prod-files [env]
  (let [{:keys [prod-target-dir copy-files]} env]
    (doseq [file copy-files]
      (copy-file file prod-target-dir))))

(defn -gen-files [env target-dir]
  (let [{:keys [gen-files gen-context]} env]
    (doseq [{:keys [gen dst]} gen-files]
      (let [dst-file (io/as-file
                       (c/concat-paths
                         [target-dir dst]))
            out-str (gen gen-context)]
        (io/make-parents (io/as-file dst-file))
        (spit dst-file out-str)
        (println "Output generated file to" (.getPath dst-file))))))

(defn gen-files [env]
  (-gen-files env (:target-dir env)))

(defn gen-prod-files [env]
  (-gen-files env (:prod-target-dir env)))

(defn clean-dir [path]
  (when (= "/" path)
    (ks/throw-str "Can't clean root directory, path:" path))
  (println "Cleaning target dir" path)
  (sh/sh "rm" "-rf" path))

(defn clean-prod-dir [{:keys [clean-prod-dir? prod-target-dir]}]
  (when clean-prod-dir?
    (clean-dir prod-target-dir)))

(defn to-file [from-file static-path output-path]
  (let [from-path (.getCanonicalPath from-file)
        static-path (.getCanonicalPath (io/as-file static-path))]
    (-> from-path
        (str/replace-first static-path "")
        (#(c/concat-paths
            [output-path %]))
        io/as-file)))

(defn copy-static-file [{:keys [from-file to-file]}]
  (io/make-parents to-file)
  (io/copy from-file to-file))

(defn copy-static [{:keys [static-path] :as env} output-path]
  (let [files (->> static-path
                   io/as-file
                   file-seq
                   (filter #(.isFile %))
                   (map (fn [from-file]
                          {:from-file from-file
                           :to-file (to-file from-file static-path output-path)})))]
    (doseq [copy-spec files]
      (copy-static-file copy-spec))
    files))

(defn spa-template [env]
  [:html
       {:style {:width "100%"
                :height "100%"}}
       (into
         [:head
          [:meta {:http-equiv "content-type"
                  :content "text/html"
                  :charset "UTF8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:link {:rel "stylesheet"
                  :href (str "/css/app.css?" (ks/now))}]
          [:link {:rel "preconnect"
                  :href "https://fonts.gstatic.com"}]
          [:link {:rel "stylesheet"
                  :href "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;700&family=Sora:wght@400;500;600;700&display=swap"}]])
       [:body {:style {:width "100%"
                       :height "100%"}}
        [:div {:id "rx-root-mount-point"
               :style {:width "100%"
                       :height "100%"
                       :display 'flex}}]
        [:script {:src (str "/cljs/app.js?" (ks/now))}]]])

(defn generate-routes [{:keys [routes-fn default-page-template-fn] :as env} output-dir]
  (when routes-fn
    (let [routes (rei/routes
                   (rei/router
                     (routes-fn env)))
          specs (->> routes
                     (map (fn [[uri-path {:keys [name template]} :as route]]
                            (let [file-path (str (if (= "/" uri-path)
                                                   "index"
                                                   (str/replace uri-path #":" "__cln__"))
                                                 ".html")]
                              {:route route
                               :template-fn (or template default-page-template-fn spa-template)
                               :output-path (c/concat-paths
                                              [output-dir file-path])}))))]
      (->> specs
           (mapv (fn [{:keys [template-fn output-path] :as spec}]
                   (try
                     (io/make-parents (io/as-file output-path))
                     (spit output-path
                       (str
                         "<!DOCTYPE html>\n"
                         (sr/render-static-markup
                           (template-fn env))))
                     (assoc spec :success? true)
                     (catch Exception e
                       (assoc spec ::anom/anom (anom/from-err e))))))
           doall))))


(defn template-nss [env]
  (concat
    (when-let [sym (:default-page-template env)]
      [(symbol (namespace sym))])))

(defn template-ns-sym? [env sym]
  (get
    (-> env
        template-nss
        set)
    sym))

(defn handle-template-change [tpl-cfg tpl-sym]
  (println "Template changed" tpl-sym))

(defn after-templates-changed [env]
  (fr/evaluate 
    (fr/repl-env)
    "location.reload()"))


(comment

  (ks/spy (let [env (c/read-env "./charly.edn")]
            (generate-routes
              env
              (c/client-dev-build-dir env))))


  (fr/evaluate 
    (fr/repl-env)
    "location.reload()")

  

  (template-nss
    (c/read-env "./charly.edn"))


  (template-ns-sym?
    (c/read-env "./charly.edn")
    'fyi.charly.templates)

  )

