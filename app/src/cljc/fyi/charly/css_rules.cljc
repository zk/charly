(ns fyi.charly.css-rules
  (:require [garden.stylesheet :refer [at-media]]
            [garden.core :as garden]))

(def bp-xs {:min-width "0px" :max-width "575px"})
(def bp-sm {:min-width "576px" :max-width "767px"})
(def bp-md {:min-width "768px" :max-width "991px"})
(def bp-<md {:max-width "991px"})
(def bp-lg {:min-width "992px" :max-width "1199px"})
(def bp-<lg {:max-width "1199px"})
(def bp-xl {:min-width "1200px"})

(defn rules [opts]
  [])
