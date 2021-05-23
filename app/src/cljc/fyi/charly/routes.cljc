(ns fyi.charly.routes
  (:require [fyi.charly.templates :as tpl]))

(defn routes [& [opts]]
  [["/" :index]])
