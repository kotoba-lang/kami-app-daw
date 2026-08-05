(ns verify-browser
  "Drive the built DAW in headless Chromium and assert what the JVM tests and
  the compiler cannot.

  This migration swapped 36 raw `<button>` elements for `ui/button`, and the
  component builds its own attribute map. If `:attrs` did not reach the
  element, every one of those buttons would render and do nothing — the app
  would look finished and be inert. That is the failure this checks for.

  Run (after `shadow-cljs release app` + `nbb scripts/gen-page.cljs`, public/
  served):
    GENKO_URL=… npx nbb --classpath \"$(clojure -Spath)\" scripts/verify-browser.cljs"
  (:require ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [promesa.core :as p]))

(def url (or (.. process -env -DAW_URL) "http://localhost:8735/"))

(defonce results (atom []))

(defn- check! [label ok? detail]
  (swap! results conj {:label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "  PASS" "  FAIL") label (if ok? "" (str "-- " detail))))

(defn- settle [page] (.waitForTimeout page 250))

(defn- checks [page errors]
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

   (fn [] (p/resolved (check! "no page errors or console errors"
                              (empty? @errors) (pr-str @errors))))])

(defn -main []
  (p/let [browser (.launch (.-chromium pw) #js {:headless true})
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
          _ (reduce (fn [acc thunk] (p/then acc (fn [_] (thunk))))
                    (p/resolved nil) (checks page errors))
          _ (.close browser)]
    (let [failed (remove :ok? @results)]
      (println)
      (println (str (count (filter :ok? @results)) "/" (count @results) " checks passed"))
      (doseq [f failed] (println " FAILED:" (:label f) "--" (:detail f)))
      (process/exit (if (seq failed) 1 0)))))

(-> (-main) (p/catch (fn [e] (println "harness error:" (str e)) (process/exit 1))))
