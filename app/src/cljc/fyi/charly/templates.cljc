(ns fyi.charly.templates
  (:require [rx.kitchen-sink :as ks]))

(defn main [opts]
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
              :href "https://fonts.googleapis.com/css2?family=Lato:wght@300;400;700&family=Noto+Serif:ital,wght@0,400;0,700;1,400&display=swap"}]])
   [:body {:style {:width "100%"
                   :height "100%"}}
    [:div {:id "rx-root-mount-point"
           :style {:width "100%"
                   :height "100%"
                   :display 'flex}}]
    [:script {:src (str "/cljs/app.js?" (ks/now))}]]])

