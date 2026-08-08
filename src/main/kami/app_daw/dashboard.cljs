(ns kami.app-daw.dashboard
  "The user-test dashboard, on the same **jp-go-dds** base as the studio.

  This was the last page of the app still on the old stack, and it was worse
  than merely out of date:

  * it linked `liquid-glass.css`, a file the DADS migration deleted — so the
    stylesheet 404'd and the page rendered with no CSS whatsoever;
  * it rendered `liquid-glass__toolbar` / `liquid-glass__panel` classes, which
    no stylesheet the app ships defines any more;
  * `init!` assigned `head.innerHTML`, replacing the entire `<head>`. On a DADS
    page that is fatal rather than merely wasteful: the design system is
    *inline*, so the first thing the page did on mount was delete it;
  * the `<noscript>` was inserted **by JavaScript**, so the one reader it exists
    for never saw it.

  The document — head, app-shell contract, noscript — now comes from
  `scripts/gen-page.cljs`, server-side. This namespace only mounts and renders,
  and everything it renders is a DADS component or a `dds-ext-*` layout helper,
  so the page contributes no CSS of its own."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [jp-go-dds.core :as dds]
            [kami.app-daw.bench :as bench]))

(defonce sessions (r/atom []))

(defn read-file!
  "Parse one exported session artifact into `sessions`."
  [file]
  (.then (.text file)
         #(swap! sessions conj (js->clj (js/JSON.parse %) :keywordize-keys true))))

(def columns ["Session" "Source" "Build" "Passed" "Failed" "Errors"])

(defn- cell
  "A session field as table text. `:source` arrives as a keyword from EDN and as
  a string from JSON; print the name either way rather than a stray colon."
  [v]
  (cond (nil? v) "—" (keyword? v) (name v) :else (str v)))

(defn session-row
  "One table row.

  The tally comes from `bench/summary` — the function the app itself uses to
  summarise a run — rather than a second copy of the same reduction. The copy
  had already drifted: it counted `(= :task-result (:kind event))`, but a
  session reaches this page as JSON, where `clj->js` has flattened that keyword
  into the string `\"task-result\"`, so every row read 0 / 0 / 0 no matter what
  the session contained."
  [s]
  (let [{:keys [passed failed errors source build]} (bench/summary s)]
    [(cell (:session-id s)) (cell source) (cell build)
     (str passed) (str failed) (str errors)]))

(defn app []
  [:main
   (dds/container
    (dds/section {}
      (dds/heading 1 "KAMI DAW · User-test dashboard")
      [:p {:class "dds-ext-lead"}
       "Import the JSON artifacts exported by actor sessions and compare them."])
    (dds/section {:title "Import"}
      (dds/form-field
       {:label "Session artifacts" :for "sessions"
        :support "One or more .json files exported from the studio's user-test panel."}
       (dds/input-text
        {:id "sessions" :type "file" :multiple true :accept "application/json"
         :on-change (fn [e]
                      (doseq [f (array-seq (.. e -target -files))] (read-file! f)))})))
    (dds/section {:title "Sessions"}
      (if-let [loaded (seq @sessions)]
        (dds/table {:caption (str (count loaded) " session"
                                  (when (> (count loaded) 1) "s") " loaded")
                    :headers columns
                    :rows (mapv session-row loaded)
                    :row-header? true})
        [:p {:class "dds-ext-lead"} "No sessions loaded yet."])))])

(defonce root (atom nil))

(defn init! []
  (when-not @root
    (reset! root (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root [app]))
