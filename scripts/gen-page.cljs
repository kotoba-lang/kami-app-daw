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
            [jp-go-dds.page :as dds-page]
            [kami.app-daw.theme :as theme]))

(def dds-root
  "Where the vendored DADS CSS lives. nbb has no resource loader; env override
  first, because a temp worktree outside the superproject (the standard shape
  for parallel agents) defeats every relative guess. Mirrors
  gftdcojp/itad's web/generate.cljs."
  (or (first (filter #(and % (fs/existsSync (str % "/resources/jp_go_dds/dds.css")))
                     [(some-> js/process .-env .-DDS_ROOT)
                      "orgs/kotoba-lang/jp-go-digital-design-system"
                      "../jp-go-digital-design-system"
                      "../../kotoba-lang/jp-go-digital-design-system"]))
      (throw (js/Error. (str "jp-go-digital-design-system の dds.css が見つからない。"
                             "DDS_ROOT で場所を渡すこと。")))))

(def dds-css (str (fs/readFileSync (str dds-root "/resources/jp_go_dds/dds.css") "utf8")))

(def out-path "public/index.html")

(defn page []
  (dds-page/->page
   {:title "KAMI DAW"
    :description "KAMI DAW — EDN-native music arrangement studio"
    :lang "ja"
    :css dds-css
    ;; DADS is a light design system; `:dark? true` is this library's own
    ;; inversion layer (not upstream's), which a DAW wants — you mix against
    ;; a dark surround.
    :dark? true
    :app-css theme/app-css*}
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
