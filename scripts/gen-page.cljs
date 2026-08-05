(ns gen-page
  "Generate `public/index.html`.

  It used to be a hand-written file carrying its own theme-color, its own
  favicon fill, and two `<link>`s — one to a stylesheet the app maintained by
  hand, one to a 39 KB liquid-glass artifact frozen into the repo. All of that
  is now a single `->page` call against `kami.app-daw.theme`, so the page
  tracks the design system instead of a snapshot of it, and picks up
  charset/viewport-fit/theme-color/data-appearance for free.

  Run:   nbb --classpath \"$(clojure -Spath)\" scripts/gen-page.cljs
  Check: same, with --check (exit 1 if the committed file is stale)"
  (:require ["node:fs" :as fs]
            ["node:process" :as process]
            [kotoba-ui.core :as ui]
            [kami.app-daw.theme :as theme]))

(def out-path "public/index.html")

(defn page []
  (ui/->page
   {:title "KAMI DAW"
    :description "KAMI DAW — EDN-native music arrangement studio"
    :lang "ja"
    :theme theme/theme
    ;; app-css after the design system's bundle: unlayered, so it wins
    ;; without a single compound selector (agent-guide rule 3).
    :head [:style [:hiccup/raw theme/app-css]]}
   ;; The app renders into this on boot. The fallback text is what a reader
   ;; with no JS sees, and matches the <noscript> the app injects.
   [:div {:id "app"} "KAMI DAW loading…"]
   [:script {:src "js/main.js"}]))

(defn -main [& args]
  (let [html (page)
        check? (some #{"--check"} args)
        current (when (fs/existsSync out-path) (str (fs/readFileSync out-path "utf8")))]
    (cond
      (and check? (= current html))
      (println "index.html up to date")

      check?
      (do (println "STALE: public/index.html differs from its generator."
                   "Run: nbb scripts/gen-page.cljs")
          (process/exit 1))

      :else
      (do (fs/writeFileSync out-path html)
          (println "wrote" out-path (count html) "bytes")))))

(apply -main (drop 2 (js->clj (.-argv process))))
