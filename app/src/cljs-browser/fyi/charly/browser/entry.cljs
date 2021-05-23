(ns fyi.charly.browser.entry
  (:require [fyi.charly.routes :as routes]
            [rx.browser :as browser]
            [reitit.core :as rei]
            [fyi.charly.browser.pages :as pages]))

(defn dispatch-route [routes handlers & [opts]]
  (let [uri (or (:uri opts)
                (browser/location-pathname))
        match (rei/match-by-path
                (rei/router routes)
                uri)]
    (if match
      (let [{:keys [data path-params]} match
            {:keys [name]} data
            handler (get handlers name)]
        (when handler
          (browser/<set-root!
            [handler (merge
                       path-params
                       (:default-opts opts))])))
      (when-let [f (:not-found opts)]
        (f)))))

(defn handlers []
  {:index pages/index})

(defn init []
  (dispatch-route
    (routes/routes)
    (handlers)
    {:not-found (fn []
                  (browser/<show-component!
                    [:div "404"]))}))

(init)



