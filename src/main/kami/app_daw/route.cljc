(ns kami.app-daw.route
  "The app's addressable views, and the fragment that addresses them.

  KAMI DAW is a **single-page app** (the kotoba-lang default — see the UI/UX
  section of the workspace CLAUDE.md and the `kotoba-uiux` skill). One document,
  one bundle, one mount; moving between the studio and the user-test dashboard
  changes state, not location.

  ── why the fragment and not a path ─────────────────────────────────────────
  These apps are served by a static host (GitHub Pages off `gh-pages`, and the
  cloud-itonami sites plane). `history.pushState` to `/user-test` gives a URL
  that works until the reader reloads it, at which point the host looks for a
  file that does not exist and answers 404. The fragment is never sent to the
  host, so a hash route survives reload, bookmarking and sharing with no server
  rewrite rule. A `404.html` covers paths the app was reached by *before* it
  became one document.

  ── why views are data ──────────────────────────────────────────────────────
  The nav is generated from `views`, so a view cannot exist without being
  reachable, and cannot be reachable without appearing in the nav. That is the
  failure this shape prevents: a view added to the dispatch `case` and forgotten
  in the nav is dead code that looks live."
  (:require [jp-go-dds.core :as dds]
            #?(:cljs [reagent.core :as r])))

(def views
  "Every view this app has, in nav order. The first is the default: the fragment
  is empty on a fresh visit, and an unknown fragment resolves here rather than
  rendering nothing."
  [{:id :studio :fragment "#/" :label "Studio"}
   {:id :user-test :fragment "#/user-test" :label "User test"}])

(def default-view (first views))

(defn fragment->view
  "Resolve a location fragment to a view. Unknown, empty and nil all land on the
  default — an address bar is user input, and a typo must not blank the app."
  [fragment]
  (let [f (or fragment "")]
    (or (first (filter #(= (:fragment %) f) views))
        ;; "#/user-test?x=1" and "#user-test" both mean the view.
        (first (filter #(and (not= "#/" (:fragment %))
                             (re-find (re-pattern (str "^#/?" (name (:id %)))) f))
                       views))
        default-view)))

(defn nav
  "The view switcher. `dds/button` with `:href` renders an anchor, so these are
  real links — middle-clickable, copyable, and readable by anything that reads
  links — while looking like the design system's own controls. The active view is
  the filled one, and says so to a screen reader."
  [active-id]
  (into [:nav {:class "dds-ext-row" :aria-label "Views"}]
        (for [{:keys [id fragment label]} views
              :let [active? (= id active-id)]]
          (dds/button label {:type (if active? :solid-fill :text)
                             :size "sm"
                             :href fragment
                             :attrs (cond-> {}
                                      active? (assoc :aria-current "page"))}))))

#?(:cljs
   (do
     (defonce current
       (r/atom (fragment->view (.. js/window -location -hash))))

     (defonce ^:private installed? (atom false))

     (defn install!
       "Start tracking the fragment. Guarded because `init!` runs again on every
       hot reload, and one listener per reload would leak."
       []
       (when-not @installed?
         (reset! installed? true)
         (.addEventListener js/window "hashchange"
                            #(reset! current (fragment->view (.. js/window -location -hash))))))))
