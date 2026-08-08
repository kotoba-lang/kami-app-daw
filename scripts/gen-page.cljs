(ns gen-page
  "Generate the app's documents: `public/index.html` — the single page — and
  `public/404.html`, the fallback a static host needs to serve it.

  It used to be a hand-written file carrying its own theme-color, its own
  favicon fill, and two `<link>`s — one to a stylesheet the app maintained by
  hand, one to a 39 KB liquid-glass artifact frozen into the repo. All of that
  is now a single `->page` call against `kami.app-daw.theme`, so the page
  tracks the design system instead of a snapshot of it, and picks up
  charset/viewport-fit/theme-color/data-appearance for free.

  There used to be a second document here, for the user-test dashboard, which
  the DADS migration had left on the old stack — it linked a stylesheet that
  migration deleted. It is a **view** now (`#/user-test`), not a document: this
  app is a single-page app, the kotoba-lang default. Two documents of one app
  meant two bundles, two shells to keep in step, and two chances to drift onto
  two design systems.

  Run:   nbb --classpath \"$(clojure -Spath)\" scripts/gen-page.cljs
  Check: same, with --check (exit 1 if a committed file is stale)"
  (:require ["node:fs" :as fs]
            ["node:process" :as process]
            [jp-go-dds.core :as dds]
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
(def not-found-path "public/404.html")

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
   ;; Served, not injected. `ui/kotoba-html-contract` used to carry this and add
   ;; it on mount — i.e. with JavaScript, which is the one condition under which
   ;; nobody can read it.
   [:noscript "KAMI DAW requires JavaScript for audio transport and rendering."]
   [:script {:src "js/main.js"}]))

(defn not-found-page
  "The static-host fallback a single-page app needs.

  GitHub Pages serves this for any path it cannot find, which is every path but
  `/` once the app is one document. The app's own views are fragments and never
  reach the host — but `user-test-dashboard.html` was a real, live URL for
  months, so that one address is sent to the view that replaced it rather than
  to a dead end.

  This is the behaviour Cloudflare spells `not_found_handling:
  single-page-application` (ADR-2606272330, ADR-2606290000); on Pages it has to
  be a document, because there is no config in which to say it."
  []
  (dds-page/->page
   {:title "KAMI DAW"
    :description "KAMI DAW — EDN-native music arrangement studio"
    :lang "en"
    :css dds-css
    :dark? true
    :app-css theme/app-css*
    :head [favicon
           [:meta {:name "robots" :content "noindex"}]]}
   [:main
    (dds/container
     (dds/section {}
       (dds/heading 1 "Not here")
       [:p {:class "dds-ext-lead"}
        "This address is not part of KAMI DAW. "
        [:a {:href "./"} "Open the app"] "."]))
    ;; Relative `./`, so one artifact is correct at github.io/kami-app-daw/ and
    ;; at any other mount point — a document cannot know its own base.
    ;;
    ;; Only the address that actually moved redirects. Rewriting *every* unknown
    ;; path to `./` would send `/x/y` to `/x/`, which is also missing, and the
    ;; fallback would redirect to itself forever. `replace`, not `assign`: a dead
    ;; URL should not be left in the reader's back button.
    [:script "if (location.pathname.endsWith('user-test-dashboard.html'))"
     " location.replace('./#/user-test');"]]))

(def documents
  [[out-path page]
   [not-found-path not-found-page]])

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
