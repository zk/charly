(ns charly.http-server
  (:require [rx.kitchen-sink :as ks]
            [aleph.http :as ah]
            [ring.util.response :as response :refer [get-header]]
            [rum.server-render :as sr]
            [charly.static-resource-middleware :as srm]
            [charly.content-type-middleware :as ctm]
            [reitit.core :as rei]
            [clojure.string :as str]))

(defonce !servers (atom nil))

(defn stop-http-server! [{:keys [port]}]
  (when-let [server (get-in @!servers [port :aleph])]
    (swap! !servers assoc-in [port :aleph] nil)
    (when server
      (.close server))))

(defn match-route [routes req]
  (rei/match-by-path
    (rei/router routes)
    (:uri req)))

(defn ns-to-js [sym]
  (-> (str sym)
      (str/replace #"-" "_")
      (str/replace #"/" ".")))

(defn create-http-handler [{:keys [dev-server routes-fn] :as opts}]
  (let [{:keys [root-path route-path-to-filename]} dev-server
        route-path-to-filename (or route-path-to-filename
                                   (fn [path]
                                     (let [path (if (= "/" path)
                                                  "index"
                                                  path)]
                                       (str (str/replace path #":" "__cln__") ".html"))))
        routes (routes-fn opts)]
    (-> (fn [req]
          (let [matched-route (match-route routes req)]
            (when matched-route
              (let [resp (-> (response/file-response
                               (if route-path-to-filename
                                 (route-path-to-filename (:template matched-route))
                                 (str (:template matched-route) ".html"))
                               {:root root-path})
                             (response/content-type "text/html"))]
                resp))))
        (srm/wrap-file root-path
          {:allow-symlinks? true})
        ctm/wrap-content-type
        ((fn [handler]
           (fn [req]
             (let [resp (handler req)]
               (if resp
                 resp
                 {:status 404}))))))))

(defn start-http-server! [{:keys [dev-server] :as opts}]
  (let [{:keys [port] :or {port 5000}} dev-server
        id port]
    (stop-http-server! dev-server)
    (swap! !servers
      assoc-in
      [id :aleph]
      (ah/start-server
        (create-http-handler opts)
        {:port port}))))
