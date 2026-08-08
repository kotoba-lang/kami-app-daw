(ns gen-page
  "Generate the app's HTML documents: `public/index.html` and
  `public/user-test-dashboard.html`.

  It used to be a hand-written file carrying its own theme-color, its own
  favicon fill, and two `<link>`s — one to a stylesheet the app maintained by
  hand, one to a 39 KB liquid-glass artifact frozen into the repo. All of that
  is now a single `->page` call against `kami.app-daw.theme`, so the page
  tracks the design system instead of a snapshot of it, and picks up
  charset/viewport-fit/theme-color/data-appearance for free.

  The dashboard was left behind by that change and kept its hand-written head —
  including a `<link>` to the liquid-glass artifact the migration had deleted,
  so it loaded no stylesheet at all. It is generated here now, from the same
  theme, so the two pages of one app cannot drift onto two design systems.

  Run:   nbb --classpath \"$(clojure -Spath)\" scripts/gen-page.cljs
  Check: same, with --check (exit 1 if a committed file is stale)"
  (:require ["node:fs" :as fs]
            ["node:process" :as process]
            [jp-go-dds.dark :as dds-dark]
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

(def favicon
  "A mark, in the design system's key colour.

  The old hand-written page carried one of these as a data URI with `#67e8f9`
  baked in; the DADS migration deleted the page and the accent with it, and
  `jp-go-dds.page` emits no icon — so both documents started asking for
  `/favicon.ico` and getting a 404 (which is what the browser harness caught,
  and what a `<link>` here fixes).

  The colour is **resolved from the vendored palette**, not stated: `resolve-dark`
  is the library's own token resolver, exposed for exactly this kind of
  out-of-CSS use, so a re-vendor moves the mark with the design system. Dark
  because both of these documents are dark."
  (let [fill (dds-dark/resolve-dark dds-css "--color-key-900")
        svg (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
                 "<rect width='32' height='32' rx='8' fill='" fill "'/></svg>")]
    [:link {:rel "icon" :type "image/svg+xml"
            :href (str "data:image/svg+xml," (js/encodeURIComponent svg))}]))

(def out-path "public/index.html")
(def dashboard-path "public/user-test-dashboard.html")

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
    :app-css theme/app-css*
    :head [favicon]}
   [:div {:id "app"} "KAMI DAW loading…"]
   [:script {:src "js/main.js"}]))

(defn dashboard-page []
  (dds-page/->page
   {:title "KAMI DAW · User-test dashboard"
    :description "Compare KAMI DAW user-test sessions exported by actor runs"
    ;; The copy on this page is English, as it was; the studio page says "ja".
    :lang "en"
    :css dds-css
    ;; Same appearance as the studio: two pages of one app, one surround.
    :dark? true
    :app-css theme/app-css*
    ;; The kotoba app-shell contract, stated in the document instead of
    ;; inserted on mount. dashboard.cljs used to assign `head.innerHTML` to
    ;; place it, which on a DADS page deletes the inline design system.
    :head [favicon
           [:meta {:name "kotoba:app-shell" :content "kami-daw user-test dashboard"}]]}
   [:div {:id "app"} "Loading…"]
   ;; A <noscript> injected by JavaScript is never read by the one reader it is
   ;; for. It belongs in the served document.
   [:noscript "Enable JavaScript to compare user-test sessions."]
   [:script {:src "js/dashboard.js"}]))

(def documents
  [[out-path page]
   [dashboard-path dashboard-page]])

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        stale (atom [])]
    (doseq [[path render] documents]
      (let [html (render)
            current (when (fs/existsSync path) (str (fs/readFileSync path "utf8")))]
        (cond
          (and check? (= current html))
          (println path "up to date")

          check?
          (do (println "STALE:" path "differs from its generator.")
              (swap! stale conj path))

          :else
          (do (fs/writeFileSync path html)
              (println "wrote" path (count html) "bytes")))))
    (when (seq @stale)
      (println "Run: nbb --classpath \"$(clojure -Spath)\" scripts/gen-page.cljs")
      (process/exit 1))))

(apply -main (drop 2 (js->clj (.-argv process))))
