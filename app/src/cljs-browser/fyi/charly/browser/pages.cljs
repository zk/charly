(ns fyi.charly.browser.pages
  (:require [rx.kitchen-sink :as ks]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.core.async :as async
             :refer [go <! timeout]]))

(defn index []
  {:render
   (fn []
     [:div "index"])})
