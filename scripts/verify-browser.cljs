(ns verify-browser
  "Drive the built DAW in headless Chromium and assert what the JVM tests and
  the compiler cannot.

  This migration swapped 36 raw `<button>` elements for `ui/button`, and the
  component builds its own attribute map. If `:attrs` did not reach the
  element, every one of those buttons would render and do nothing — the app
  would look finished and be inert. That is the failure this checks for.

  It also drives `user-test-dashboard.html`, whose failure mode was the
  opposite: that page mounted fine and was **unstyled**, because it linked a
  stylesheet the migration had deleted and then replaced `<head>` wholesale on
  mount. Neither the compiler nor a JVM test can see either of those — the first
  is a 404, the second is a DOM mutation. Both are checked here.

  Run (after `shadow-cljs release app dashboard` + `nbb scripts/gen-page.cljs`,
  with public/ served):
    DAW_URL=… npx nbb --classpath \"$(clojure -Spath)\" scripts/verify-browser.cljs"
  (:require ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def url (or (.. process -env -DAW_URL) "http://localhost:8735/"))

(def dashboard-url
  (str (if (str/ends-with? url "/") url (str url "/")) "user-test-dashboard.html"))

(def session-fixture
  "One exported session, in the JSON shape `bench/export-json!` writes: two
  passes, one failure, four errors. The dashboard has to agree with those
  numbers — it used to print 0 / 0 / 0 for every session, because it matched
  `:kind` against a keyword and JSON carries a string."
  (js/JSON.stringify
   #js {:schema "kami.user-test/v2"
        :session-id "daw-fixture"
        :actor-id "bedroom-producer"
        :build "fixture"
        :source "synthetic"
        :events #js [#js {:kind "task-result" :task "Create a project"
                          :success? true :errors 0 :duration-ms 1000}
                     #js {:kind "task-result" :task "Record a sound"
                          :success? true :errors 1 :duration-ms 2000}
                     #js {:kind "task-result" :task "Export a WAV"
                          :success? false :errors 3 :duration-ms 3000}]}))

(defonce results (atom []))

(defn- check! [label ok? detail]
  (swap! results conj {:label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "  PASS" "  FAIL") label (if ok? "" (str "-- " detail))))

(defn- settle [page] (.waitForTimeout page 250))

(def launch-opts
  "Playwright's own Chromium is the default. `PW_CHANNEL=chrome` runs the
  installed Google Chrome instead — every assertion below is about the page, not
  about which build of Chromium renders it, and a machine whose ms-playwright
  cache is unavailable should still be able to run this."
  (let [o #js {:headless true}
        channel (str (or (.. process -env -PW_CHANNEL) ""))]
    (when (seq channel) (aset o "channel" channel))
    o))

(defn- run-all
  "Run the thunks in order — each check depends on the DOM the previous one left."
  [thunks]
  (reduce (fn [acc thunk] (p/then acc (fn [_] (thunk)))) (p/resolved nil) thunks))

(defn- checks [page]
  [;; The app mounted at all.
   (fn [] (p/let [t (.textContent (.locator page "h1"))]
            (check! "app mounted" (= "KAMI DAW" (str t)) t)))

   ;; Every button is a design-system button now.
   ;; Every button is a DADS button now — except the timeline clips, which are
   ;; absolutely positioned elements that happen to be clickable and were given
   ;; their own <button> back rather than pretending to be a DADS control.
   (fn [] (p/let [dads (.count (.locator page "button.dads-button"))
                  other (.count (.locator page "button:not(.dads-button)"))
                  clips (.count (.locator page "button.daw-clip"))]
            (check! "buttons are DADS buttons; only clips are not"
                    (and (> dads 20) (= other clips))
                    (str "dads=" dads " other=" other " clips=" clips))))

   ;; THE check: :attrs passthrough means those buttons still work. Play
   ;; toggles the transport, and its label is derived from that state.
   (fn [] (p/let [sel "button[data-type='solid-fill']"
                  before (.textContent (.first (.locator page sel)))
                  _ (.click (.first (.locator page sel)))
                  _ (settle page)
                  after (.textContent (.first (.locator page sel)))]
            (check! "ui/button :on-click reaches the app (transport toggles)"
                    (not= (str before) (str after))
                    (str (str before) " -> " (str after)))))

   (fn [] (p/let [sel "button[data-type='solid-fill']"
                  _ (.click (.first (.locator page sel)))   ; stop again
                  _ (settle page)
                  t (.textContent (.first (.locator page sel)))]
            (check! "and toggles back" (re-find #"Play" (str t)) t)))

   ;; A track's mute button is one of the 36; it flips its own label.
   (fn [] (p/let [btn (.first (.locator page ".daw-track-head button:has-text('M')"))
                  before (.textContent btn)
                  _ (.click btn)
                  _ (settle page)
                  after (.textContent (.first (.locator page ".daw-track-head button:has-text('M')")))]
            (check! "per-track ui/button mutates project state"
                    (not= (str before) (str after))
                    (str (str before) " -> " (str after)))))

   ;; The timeline still lays out: track heads, lanes, clips.
   (fn [] (p/let [tracks (.count (.locator page ".daw-track"))
                  clips (.count (.locator page ".daw-clip"))]
            (check! "timeline renders tracks and clips"
                    (and (>= tracks 3) (>= clips 3)) (str "tracks=" tracks " clips=" clips))))

   ;; Clip colors come from the palette, so they must resolve to real colors
   ;; rather than to the empty string a missing custom property gives.
   (fn [] (p/let [bg (.evaluate page "getComputedStyle(document.querySelector('.daw-clip')).backgroundColor")]
            (check! "clip color resolves from the HIG palette"
                    (and (seq (str bg)) (not= "rgba(0, 0, 0, 0)" (str bg))) bg)))

   ;; The theme reached the cascade, and the dark appearance is stamped.
   ;; The --hig-* contract now resolves onto DADS primitives. getPropertyValue
   ;; resolves var() chains, so the proof is that the bridged token reads back
   ;; as the DADS value itself and the grid resolves to a real length.
   (fn [] (p/let [tint (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-tint').trim()")
                  key (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--color-key-900').trim()")
                  gap (.evaluate page "getComputedStyle(document.querySelector('.daw-toolbar')).gap")]
            (check! "the --hig-* contract resolves onto DADS primitives"
                    (and (= (str tint) (str key)) (seq (str key)) (re-find #"^\d" (str gap)))
                    (str "tint=" tint " key=" key " gap=" gap))))

   ;; The frozen artifact is gone: no request for it, and the tokens it
   ;; lacked are present.
   (fn [] (p/let [label (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-label').trim()")
                  dads (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--font-family-sans').trim()")]
            (check! "DADS is the base and the bridge sits on it"
                    (and (seq (str label)) (seq (str dads))) (str label " | " dads))))

   ])

(defn- dashboard-checks
  "The second document of the same app. Everything here is a DADS component or a
  `dds-ext-*` helper, so the page ships no CSS of its own — which makes these
  checks about whether the design system *arrives and survives*."
  [page]
  [(fn [] (p/let [t (.textContent (.locator page "main h1"))]
            (check! "dashboard mounted" (= "KAMI DAW · User-test dashboard" (str t)) t)))

   ;; The page carried `<link href="liquid-glass.css">` to a file the DADS
   ;; migration deleted, and rendered `liquid-glass__*` classes no stylesheet
   ;; defines. A 404 stylesheet leaves a page that looks broken but reports
   ;; nothing; the response listener above turns it into a failure.
   (fn [] (p/let [n (.count (.locator page "[class*='liquid-glass']"))
                  links (.count (.locator page "link[href*='liquid-glass']"))]
            (check! "nothing left of the liquid-glass stack"
                    (and (zero? n) (zero? links)) (str "classes=" n " links=" links))))

   ;; The regression that made this page unstyleable: `init!` assigned
   ;; `head.innerHTML`, and a DADS page carries its stylesheet *inline*, so the
   ;; first thing the app did on mount was delete the design system. Asserting
   ;; the tokens resolve *after* mount is what catches it.
   (fn [] (p/let [styles (.count (.locator page "head style"))
                  tint (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-tint').trim()")
                  key (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--color-key-900').trim()")]
            (check! "the design system survives mount"
                    (and (pos? styles) (seq (str key)) (= (str tint) (str key)))
                    (str "styles=" styles " tint=" tint " key=" key))))

   (fn [] (p/let [meta (.count (.locator page "head meta[name='kotoba:app-shell']"))]
            (check! "app-shell contract is in the document" (= 1 meta) meta)))

   ;; It used to be injected by JavaScript, i.e. never shown to a reader without
   ;; JavaScript.
   (fn [] (p/let [html (.evaluate page "document.querySelector('noscript')?.textContent ?? ''")]
            (check! "noscript is served, not injected"
                    (str/includes? (str html) "JavaScript") (str html))))

   (fn [] (p/let [n (.count (.locator page "input[type='file'].dads-input-text__input"))
                  label (.count (.locator page ".dads-form-control-label__label"))]
            (check! "the file control is a DADS form field"
                    (and (= 1 n) (pos? label)) (str "input=" n " label=" label))))

   ;; Import a real artifact and read the tally back out of the rendered table.
   (fn [] (p/let [_ (.setInputFiles (.locator page "input[type='file']")
                                    #js {:name "daw-fixture.json"
                                         :mimeType "application/json"
                                         :buffer (js/Buffer.from session-fixture "utf8")})
                  _ (.waitForSelector page ".dads-table__table tbody tr" #js {:timeout 10000})
                  rows (.count (.locator page ".dads-table__table tbody tr"))
                  cells (.allTextContents (.locator page ".dads-table__table tbody tr td"))
                  head (.textContent (.locator page ".dads-table__table tbody tr th"))]
            (check! "an imported session tallies its task results"
                    (and (= 1 rows) (= ["synthetic" "fixture" "2" "1" "4"] (js->clj cells))
                         (= "daw-fixture" (str head)))
                    (str head " " (pr-str (js->clj cells))))))])

(defn -main []
  (p/let [browser (.launch (.-chromium pw) launch-opts)
          ctx (.newContext browser #js {:viewport #js {:width 1440 :height 900}})
          page (.newPage ctx)
          errors (atom [])
          noise? (fn [t] (re-find #"cdn-cgi" (str t)))
          _ (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
          _ (.on page "console" (fn [m]
                                  (let [loc (some-> (.location m) (aget "url"))]
                                    (when (and (= "error" (.type m))
                                               (not (noise? (.text m)))
                                               (not (noise? loc)))
                                      (swap! errors conj (str (.text m) " @ " loc))))))
          _ (.on page "response" (fn [r] (when (and (>= (.status r) 400) (not (noise? (.url r))))
                                           (swap! errors conj (str (.status r) " " (.url r))))))
          _ (.goto page url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page ".daw-track" #js {:timeout 15000})
          _ (run-all (checks page))
          _ (.goto page dashboard-url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page "main h1" #js {:timeout 15000})
          _ (run-all (dashboard-checks page))
          ;; Last, so it covers both documents — including the 404 the deleted
          ;; liquid-glass.css link produced on the dashboard.
          _ (p/resolved (check! "no page errors, console errors or 4xx responses"
                                (empty? @errors) (pr-str @errors)))
          _ (.close browser)]
    (let [failed (remove :ok? @results)]
      (println)
      (println (str (count (filter :ok? @results)) "/" (count @results) " checks passed"))
      (doseq [f failed] (println " FAILED:" (:label f) "--" (:detail f)))
      (process/exit (if (seq failed) 1 0)))))

(-> (-main) (p/catch (fn [e] (println "harness error:" (str e)) (process/exit 1))))
