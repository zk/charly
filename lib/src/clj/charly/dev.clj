(ns charly.dev
  (:require [rx.kitchen-sink :as ks]
            [charly.compiler :as c]
            [charly.watch :as w]
            [charly.http-server :as hs]
            [figwheel.main.api :as fapi]
            [rum.server-render :as sr]
            [nrepl.cmdline :as nrepl]
            [nrepl.server :as nrepl-server]))

(defonce !nrepl-server (atom nil))

(defn start-clj-repl []
  (let [nrepl-opts (nrepl/server-opts
                     {:middleware
                      '[cider.nrepl/cider-middleware
                        cider.piggieback/wrap-cljs-repl]})]
    (when @!nrepl-server
      (nrepl-server/stop-server @!nrepl-server))


    (let [server (nrepl/start-server nrepl-opts)]
      (reset! !nrepl-server server)
      (println (nrepl/server-started-message server nrepl-opts))
      (nrepl/save-port-file server nrepl-opts))))

(comment

  (nrepl/server-opts
    {:middleware
     '[cider.nrepl/cider-middleware
       cider.piggieback/wrap-cljs-repl]})

  (nrepl/connection-opts
    {:middleware
     [cider.nrepl/cider-middleware
      cider.piggieback/wrap-cljs-repl]})

  (start-clj-repl)

  (prn server)

  (nrepl-server/stop-server server)

  (ks/pp (nrepl/args->cli-options ["--middleware" "[cider.nrepl/cider-middleware,cider.piggieback/wrap-cljs-repl]"]))

  )

(defn start-dev! [env]
  (c/compile env)
  (w/start-watch-static! env)
  (hs/start-http-server! env)
  (c/start-figwheel-server! env)
  (start-clj-repl))

(defn restart-dev! [env]
  (c/compile env)
  (w/start-watch-static! env)
  (hs/start-http-server! env)
  (c/start-figwheel-server! env))

(defn restart-http-server [env]
  (hs/start-http-server! env))

(defn cljs-repl []
  (fapi/cljs-repl "charly-cljs"))

(defn test-env []
  {:clean-target-dir? true
   :http-root-path "target/wel/prod/public"
   :project-root "resources/wel"
   :target-dir "target/wel/prod"
   :prod-target-dir "/Users/zk/code/welcome/docs"
   :copy-dirs [{:src "resources/wel/static/html"
                :dst "public"}
               {:src "resources/wel/static/css"
                :dst "public/css"}
               {:src "resources/wel/static/img"
                :dst "public/img"}]
   :copy-files [#_{:src "resources/wel/test-file.json"
                   :dst "test-file.json"}]
   :gen-context {:foo "bar"}
   :gen-files (->> ["public/index.html"
                    "public/auth0-logout.html"
                    "public/auth0-cb.html"
                    "public/auto0-magic-link-test.html"]
                   (map (fn [file-name]
                          {:gen (fn [env]
                                  (str
                                    "<!DOCTYPE html>\n"
                                    (sr/render-static-markup
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
                                                  :href "/css/app.css"}]
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
                                        [:script {:src (str "/cljs/app.js?" (ks/now))}]]])))
                           :dst file-name})))
   :css {:garden {:preamble ["resources/rx-css-reset/reset.css"
                             "resources/public/css/bootstrap-grid.min.css"]}
         :outs [{:watch-paths ["src/cljc/wel/site_css_rules.cljc"]
                 :rules-fn 'wel.site-css-rules/rules
                 :output-to "css/app.css"}]}
   #_#_:routes [["/" {:cljs 'pages.root/render}]
                ["/auth0-cb" {:cljs 'pages.root/render}]
                ["/auth0-logout" {:cljs 'pages.root/render}]]
   :dev-server {:root-path "target/wel/prod/public"
                :port 5000}
   :cljs {:figwheel {}
          :compiler
          {:main 'wel.browser.entry
           :recompile-dependents true
           :parallel-build true
           :hashbang false
           :foreign-libs
           [{:file "resources/dexie/dexie.min.js"
             :file-min "resources/dexie/dexie.min.js"
             :provides ["dexie"]}
            {:file "resources/tonejs/Tone.js"
             :file-min "resources/tonejs/Tone.js"
             :provides ["tonejs"]}
            {:file "resources/onlycrayons/aws-sdk/aws-sdk.min.js"
             :file-min "resources/onlycrayons/aws-sdk/aws-sdk.min.js"
             :provides ["aws-sdk"]}
            {:file "resources/pdfjs/pdfjs-2.6.347.js"
             :file-min "resources/pdfjs/pdfjs-2.6.347.min.js"
             :provides ["pdfjs"]}
            {:file "resources/auth0/auth0-spa-js.production.js"
             :file-min "resources/auth0/auth0-spa-js.production.js"
             :provides ["auth0-spa-js"]}
            {:file "resources/auth0/auth0.min.js"
             :file-min "resources/auth0/auth0.min.js"
             :provides ["auth0"]}]}}})

(defn prod-env []
  {:clean-target-dir? false
   :http-root-path "/Users/zk/code/welcome/docs"
   :project-root "resources/wel"
   :target-dir "/Users/zk/code/wc-site-deploy/docs"
   :copy-dirs [{:src "resources/wel/static/html"
                :dst ""}
               {:src "resources/wel/static/css"
                :dst "css"}
               {:src "resources/wel/static/img"
                :dst "img"}]
   :copy-files [#_{:src "resources/wel/test-file.json"
                   :dst "test-file.json"}]
   :gen-context {:foo "bar"}
   :gen-files (->> ["index.html"
                    "auth0-logout.html"
                    "auth0-cb.html"
                    "auto0-magic-link-test.html"]
                   (map (fn [file-name]
                          {:gen (fn [env]
                                  (str
                                    "<!DOCTYPE html>\n"
                                    (sr/render-static-markup
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
                                                  :href "/css/app.css"}]
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
                                        [:script {:src (str "/cljs/app.js?" (ks/now))}]]])))
                           :dst file-name})))
   :css {:garden {:preamble ["resources/rx-css-reset/reset.css"
                             "resources/public/css/bootstrap-grid.min.css"]}
         :outs [{:watch-paths ["src/cljc/wel/site_css_rules.cljc"]
                 :rules-fn 'wel.site-css-rules/rules
                 :output-to "css/app.css"}]}
   :routes [["/" {:cljs 'pages.root/render}]]
   :dev-server {:root-path "target/wel/prod/public"
                :port 5000}
   :cljs {:figwheel {}
          :compiler
          {:main 'wel.browser.entry
           :recompile-dependents true
           :parallel-build true
           :optimizations :advanced
           :closure-warnings {:externs-validation :off}
           :hashbang false
           :foreign-libs
           [{:file "resources/dexie/dexie.min.js"
             :file-min "resources/dexie/dexie.min.js"
             :provides ["dexie"]}
            {:file "resources/tonejs/Tone.js"
             :file-min "resources/tonejs/Tone.js"
             :provides ["tonejs"]}
            {:file "resources/onlycrayons/aws-sdk/aws-sdk.min.js"
             :file-min "resources/onlycrayons/aws-sdk/aws-sdk.min.js"
             :provides ["aws-sdk"]}
            {:file "resources/pdfjs/pdfjs-2.6.347.js"
             :file-min "resources/pdfjs/pdfjs-2.6.347.min.js"
             :provides ["pdfjs"]}
            {:file "resources/auth0/auth0-spa-js.production.js"
             :file-min "resources/auth0/auth0-spa-js.production.js"
             :provides ["auth0-spa-js"]}
            {:file "resources/auth0/auth0.min.js"
             :file-min "resources/auth0/auth0.min.js"
             :provides ["auth0"]}]
           :externs ["resources/dexie/dexie.ext.js"
                     "resources/aws-sdk/aws-sdk-js.ext.js"
                     "resources/stripe/stripe.ext.js"
                     "resources/tonejs/Tone.ext.js"
                     "resources/onlycrayons/aws-sdk/aws-sdk.ext.js"
                     "resources/gapi/gapi.ext.js"
                     "resources/auth0/auth0-spa-js.ext.js"
                     #_"resources/auth0/auth0.ext.js"
                     "resources/auth0/auth0.min.js"]}}})

(defn do-test []
  (start-dev! (test-env)))

(comment

  (do-test)

  (cljs-repl)

  (restart-http-server (test-env))

  (w/start-watch-static! (test-env))

  (c/compile (test-env))

  (c/compile-prod (test-env))

  (do
    (c/compile (prod-env))
    (c/compile-prod-cljs (prod-env)))

  )


(def test-config)

