(ns charly.config
  (:require [rx.kitchen-sink :as ks]
            [rx.anom :as anom]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as f]
            [clojure.tools.namespace.file :as nsf]
            [clojure.tools.namespace.parse :as parse]
            [clojure.java.classpath :as cp])
  (:import [java.util.jar JarFile]))

(defn file-paths-for-namespace [root-path ns-sym]
  (get (->> (f/find-sources-in-dir (io/file root-path))
            (map (fn [file]
                   [(-> file
                        nsf/read-file-ns-decl
                        parse/name-from-ns-decl)
                    (.getPath file)]))
            (group-by first)
            (map (fn [[k vs]]
                   [k
                    (->> vs
                         (mapv second))]))
            (into {}))
    ns-sym))

(defn concat-paths [parts]
  (str/replace
    (->> parts
         (remove nil?)
         (interpose "/")
         (apply str))
    #"/+" "/"))

(defn read-config [path]
  (try
    (ks/edn-read-string (slurp path))
    (catch Exception e
      (anom/from-err e))))

(defn expand-project-root [config]
  (let [{:keys [project-root]
         :or {project-root ""}} config]
    config))

(defn expand-build-paths [config]
  (let [{:keys [project-root
                build-path]
         :or {build-path "build"}} config]
    (merge
      {:dev-output-path (concat-paths
                          [project-root build-path "dev"])
       :api-dev-output-path (concat-paths
                              [project-root build-path "api" "dev"])
       :prod-output-path (concat-paths
                           [project-root build-path "prod" "site"])
       :api-prod-output-path (concat-paths
                               [project-root build-path "prod" "api-lambda"])}
      config)))

(defn expand-static-path [{:keys [project-root] :as config}]
  (merge
    {:static-path (concat-paths
                    [project-root "static"])}
    config))

(defn resolve-sym [sym & [opts]]
  (let [sym (str sym)
        sym (symbol (str/replace sym "'" ""))

        ns (namespace sym)
        ns (symbol ns)]
    (let [var (resolve sym)]
      (if var
        (let [var-val (var-get var)]
          (if (fn? var-val)
            var-val
            (fn [_] var-val)))
        (anom/anom {:desc "Couldn't resolve sym"
                    :sym sym})))))

(defn resolve-var [sym & [opts]]
  (let [sym (str sym)
        sym (symbol (str/replace sym "'" ""))

        ns (namespace sym)
        ns (symbol ns)]
    (let [var (resolve sym)]
      (if var
        var
        (anom/anom {:desc "Couldn't resolve var"
                    :sym sym})))))

(defn expand-routes [{:keys [project-root client-routes default-page-template] :as config}]
  (let [routes-fn (resolve-sym client-routes)
        default-page-template-fn (when default-page-template
                                   (resolve-sym default-page-template))
        file-paths (file-paths-for-namespace
                     (concat-paths
                       [project-root "src"])
                     (symbol (namespace client-routes)))]
    (merge
      {:routes-fn routes-fn}
      (when default-page-template-fn
        {:default-page-template-fn default-page-template-fn})
      (when file-paths
        {:routes-file-paths file-paths})
      config)))

(defn expand-css [{:keys [css-files css-preamble project-root] :as config}]
  (merge
    config
    {:css-files (->> css-files
                     (map (fn [{:keys [rules path] :as spec}]
                            (let [rules-ns-sym (symbol (namespace rules))]
                              (merge
                                spec
                                {;;:rules-fn (resolve-sym rules)
                                 :rules-var (resolve-var rules)
                                 :rules-ns-sym rules-ns-sym
                                 :rules-path (first
                                               (file-paths-for-namespace
                                                 (concat-paths
                                                   [project-root "src"])
                                                 rules-ns-sym))})))))
     :css-preamble-fq (->> css-preamble
                           (mapv #(concat-paths
                                    [project-root %])))}))

(defn expand-dev-server [{:keys [project-root] :as config}]
  (merge
    config
    {:dev-server {:root-path (concat-paths
                               [project-root "build/dev"])
                  :route-path-to-filename (fn [path]
                                            (let [path (if (= "/" path)
                                                         "index"
                                                         path)]
                                              (str (str/replace path #":" "__cln__") ".html")))
                  :port 5000}}))

(defn parse-env [s]
  (->> (str/split s #"\n+")
       (map (fn [s]
              (str/split s #"=")))
       (into {})))

(defn parse-env-paths [paths]
  (->> paths
       (map (fn [f]
              (try
                (slurp f)
                (catch Exception e
                  nil))))
       (remove nil?)
       (map parse-env)
       (reduce merge)))

(defn expand-env-vars [{:keys [project-root runtime-env] :as config}]
  (let [dev-env-file-props (->> [".env"
                                 ".env.dev"
                                 ".env.dev.local"]
                                (map #(concat-paths
                                        [project-root %]))
                                parse-env-paths)
        prod-env-file-props (->> [".env"
                                  ".env.prod"
                                  ".env.prod.local"]
                                 (map #(concat-paths
                                         [project-root %]))
                                 parse-env-paths)
        dev-env (merge dev-env-file-props (System/getenv))
        prod-env (merge prod-env-file-props (System/getenv))]
    (merge
      config
      (if (= runtime-env :dev)
        (when-not (empty? dev-env)
          {:env-vars dev-env 
           :client-env-vars (->> dev-env
                                 (filter (fn [[k v]]
                                           (str/starts-with? k "CHARLY_PUB")))
                                 (into {}))})
        (when-not (empty? prod-env)
          {:env-vars prod-env
           :client-env-vars (->> prod-env
                                 (filter (fn [[k v]]
                                           (str/starts-with? k "CHARLY_PUB")))
                                 (into {}))})))))

(defn expand-runtime-env [config]
  (merge
    config
    {:runtime-env (or (:runtime-env config) :prod)}))

(defn config->env [config]
  (-> config
      expand-runtime-env
      expand-project-root
      expand-build-paths
      expand-static-path
      expand-routes
      expand-css
      expand-dev-server
      expand-env-vars))

(defn read-env [path]
  (config->env (read-config path)))

(defn client-dev-build-dir [env]
  (:dev-output-path env))
