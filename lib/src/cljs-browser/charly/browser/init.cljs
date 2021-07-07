(ns charly.browser.init
  (:require [rx.kitchen-sink :as ks]
            [rx.browser :as browser]
            [reitit.core :as rei]))

(defonce !pop-state-handler (atom nil))

(defn bind-pop-state [f]
  (when @!pop-state-handler
    (.removeEventListener js/window "popstate" @!pop-state-handler))
  (reset! !pop-state-handler f)
  (.addEventListener js/window "popstate" f))

(defn dispatch-route [routes handlers & [opts]]
  (let [uri (or (:uri opts)
                (browser/location-pathname))
        match (rei/match-by-path
                (rei/router routes)
                uri)
        verbose? (:verbose? opts)]
    (when verbose?
      (println "Dispatching" uri)
      (println "Match" (pr-str match)))
    (if match
      (let [{:keys [data path-params]} match
            {:keys [name]} data
            handler (get handlers name)]
        (when verbose?
          (if handler
            (println "Handler" handler)
            (println "! No handler found")))
        (when handler
          (let [res (handler (merge
                               path-params
                               (:default-opts opts)))]
            (when verbose?
              (println "Handler result:" res))
            (when (and (map? res) (:render res))
              (browser/<show-component!
                (:render res)
                {:scroll-to-top? true})))))
      (when-let [f (:not-found opts)]
        (f)))))

(defn init [{:keys [routes handlers not-found verbose?] :as opts}]
  (bind-pop-state
    (fn []
      (dispatch-route
        routes
        handlers
        {:not-found not-found
         :verbose? verbose?})))
  (dispatch-route routes handlers
    {:not-found not-found
     :verbose? verbose?}))

