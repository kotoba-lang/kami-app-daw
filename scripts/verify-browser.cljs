(ns verify-browser
  "Drive the built DAW in headless Chromium and assert what the JVM tests and
  the compiler cannot.

  This migration swapped 36 raw `<button>` elements for `ui/button`, and the
  component builds its own attribute map. If `:attrs` did not reach the
  element, every one of those buttons would render and do nothing — the app
  would look finished and be inert. That is the failure this checks for.

  It also drives the user-test view, and the claim that makes this a
  single-page app rather than two pages sharing a stylesheet: crossing between
  views must **not** load a document. That is unobservable from the source — the
  code reads the same whether the nav is a router link or a plain href — so it is
  checked by leaving a value on `window`, crossing, and finding it still there.

  Run (after `shadow-cljs release app` + `nbb scripts/gen-page.cljs`, with
  public/ served):
    DAW_URL=… npx nbb --classpath \"$(clojure -Spath)\" scripts/verify-browser.cljs"
  (:require ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def url (or (.. process -env -DAW_URL) "http://localhost:8735/"))

(def root-url (if (str/ends-with? url "/") url (str url "/")))

(def legacy-dashboard-url
  "The address the dashboard had while it was a document. It was live for months;
  `404.html` has to send it to the view that replaced it."
  (str root-url "user-test-dashboard.html"))

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
   (fn [] (p/let [btn (.first (.locator page "button[aria-label^='Mute ']"))
                  before (.textContent btn)
                  _ (.click btn)
                  _ (settle page)
                  after (.textContent (.first (.locator page "button[aria-label^='Mute ']")))]
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

(defn- spa-checks
  "One document, one bundle, and views reached without leaving it."
  [page]
  [(fn [] (p/let [scripts (.count (.locator page "script[src]"))]
            (check! "the app ships one bundle" (= 1 scripts) scripts)))

   (fn [] (p/let [links (.count (.locator page "nav[aria-label='Views'] a.dads-button"))
                  current (.count (.locator page "nav[aria-label='Views'] a[aria-current='page']"))]
            (check! "the nav is generated from the view table"
                    (and (= 2 links) (= 1 current)) (str "links=" links " current=" current))))

   ;; The single-page claim itself. If the nav were plain navigation this value
   ;; would be gone after the crossing, and every other check here would still
   ;; pass — which is why it is asserted rather than assumed.
   (fn [] (p/let [_ (.evaluate page "window.__spaWitness = 'alive'")
                  _ (.click (.locator page "nav[aria-label='Views'] a:has-text('User test')"))
                  _ (.waitForSelector page "#sessions" #js {:timeout 15000})
                  witness (.evaluate page "window.__spaWitness ?? 'gone'")
                  hash (.evaluate page "location.hash")
                  t (.textContent (.locator page "main h1"))]
            (check! "crossing to the user-test view does not load a document"
                    (and (= "alive" (str witness))
                         (= "#/user-test" (str hash))
                         (= "KAMI DAW · User-test dashboard" (str t)))
                    (str witness " " hash " " t))))

   ;; And back — a one-way router is a link, not a router.
   (fn [] (p/let [_ (.click (.locator page "nav[aria-label='Views'] a:has-text('Studio')"))
                  _ (settle page)
                  witness (.evaluate page "window.__spaWitness ?? 'gone'")
                  tracks (.count (.locator page ".daw-track"))]
            (check! "and back to the studio, still without a document load"
                    (and (= "alive" (str witness)) (>= tracks 2))
                    (str witness " tracks=" tracks))))

   ;; The studio's own state has to outlive a view change; if it did not, the
   ;; single page would be buying nothing.
   ;;
   ;; A track's mute, not the transport. The transport looks like the better
   ;; witness — "a DAW that stops playing because you opened a report is not one
   ;; app" — but playback also ends by itself when it reaches the end of this
   ;; short demo song, so asserting on it is a race, and one that only loses on a
   ;; slow enough network. It passed locally and failed against the live site.
   ;; A mute flag is project state: it changes only when something changes it.
   ;;
   ;; `settle` before reading: without it this read lands before React has
   ;; re-rendered and returns the *pre-click* label, which then differs from the
   ;; settled read after the crossing — a failure that looks exactly like lost
   ;; state and is not (measured: it reported "M ✓ -> M" while the app's own
   ;; autosave envelope showed the flag was never lost).
   (fn [] (p/let [sel "button[aria-label^='Mute ']"
                  _ (.click (.first (.locator page sel)))
                  _ (settle page)
                  flipped (.textContent (.first (.locator page sel)))
                  _ (.click (.locator page "nav[aria-label='Views'] a:has-text('User test')"))
                  _ (.waitForSelector page "#sessions" #js {:timeout 15000})
                  _ (.click (.locator page "nav[aria-label='Views'] a:has-text('Studio')"))
                  _ (.waitForSelector page ".daw-track" #js {:timeout 15000})
                  after (.textContent (.first (.locator page sel)))]
            (check! "studio project state survives the round trip"
                    (= (str flipped) (str after)) (str flipped " -> " after))))])

(defn- dashboard-checks
  "The user-test view. Everything in it is a DADS component or a `dds-ext-*`
  helper, so it ships no CSS of its own — which makes these checks about whether
  the design system *arrives and survives*."
  [page]
  [(fn [] (p/let [t (.textContent (.locator page "main h1"))]
            (check! "user-test view mounted" (= "KAMI DAW · User-test dashboard" (str t)) t)))

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

   (fn [] (p/let [n (.count (.locator page "nav[aria-label='Views'] a"))]
            (check! "the user-test view can be left again" (= 2 n) n)))

   ;; It used to be injected by JavaScript, i.e. never shown to a reader without
   ;; JavaScript.
   (fn [] (p/let [html (.evaluate page "document.querySelector('noscript')?.textContent ?? ''")]
            (check! "noscript is served, not injected"
                    (str/includes? (str html) "JavaScript") (str html))))

   (fn [] (p/let [n (.count (.locator page "#sessions.dads-input-text__input"))
                  label (.count (.locator page ".dads-form-control-label__label"))]
            (check! "the file control is a DADS form field"
                    (and (= 1 n) (pos? label)) (str "input=" n " label=" label))))

   ;; Import a real artifact and read the tally back out of the rendered table.
   ;; `#sessions`, not `input[type=file]`: the studio has several of those, and
   ;; asking for the bare selector on a single-page app is ambiguous the moment
   ;; the render is a beat behind (which is exactly what happened against the
   ;; live site).
   (fn [] (p/let [_ (.setInputFiles (.locator page "#sessions")
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
          _ (.goto page root-url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page ".daw-track" #js {:timeout 15000})
          _ (run-all (checks page))
          _ (run-all (spa-checks page))
          ;; spa-checks leaves us on the studio; cross once more for the view's
          ;; own checks.
          _ (.click (.locator page "nav[aria-label='Views'] a:has-text('User test')"))
          ;; Wait for something only this view has. `main h1` is in both views,
          ;; so waiting on it returns before the crossing has rendered.
          _ (.waitForSelector page "#sessions" #js {:timeout 15000})
          _ (run-all (dashboard-checks page))
          ;; The one address that predates the single page. This is a real
          ;; document load, and the host answers 404 before serving 404.html —
          ;; which is the mechanism, not a fault, so it is dropped from `errors`
          ;; rather than allowed to fail the run.
          _ (.goto page legacy-dashboard-url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page "#sessions" #js {:timeout 15000})
          _ (p/let [hash (.evaluate page "location.hash")
                    t (.textContent (.locator page "main h1"))]
              (swap! errors (fn [es] (remove #(str/includes? (str %) "user-test-dashboard.html") es)))
              (check! "the dashboard's old URL still reaches its view"
                      (and (= "#/user-test" (str hash))
                           (= "KAMI DAW · User-test dashboard" (str t)))
                      (str hash " " t)))
          _ (p/resolved (check! "no page errors, console errors or 4xx responses"
                                (empty? @errors) (pr-str @errors)))
          _ (.close browser)]
    (let [failed (remove :ok? @results)]
      (println)
      (println (str (count (filter :ok? @results)) "/" (count @results) " checks passed"))
      (doseq [f failed] (println " FAILED:" (:label f) "--" (:detail f)))
      (process/exit (if (seq failed) 1 0)))))

(-> (-main) (p/catch (fn [e] (println "harness error:" (str e)) (process/exit 1))))
