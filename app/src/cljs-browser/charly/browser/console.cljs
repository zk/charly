(ns charly.browser.console
  (:require [rx.kitchen-sink :as ks]
            [figwheel.repl :as repl]
            [cljs.repl :as cr]))

(defn index [_]
  {:render
   (fn []
     [:div "charly console"])})

(comment

  (repl/connected)

  


  )



