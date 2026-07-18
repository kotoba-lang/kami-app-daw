(ns kami.app-daw.ui (:require [reagent.core :as r] [reagent.dom.client :as rdom]
                              [cljs.reader :as reader] [kami.app-daw.core :as daw]))
(def sample (daw/project {:project/id "demo-song" :project/name "夜明けの波形"
 :project/tracks [{:track/id "drums" :track/name "Drums" :track/color "#ff8a65" :track/gain 0.82
                   :track/clips [{:clip/id "beat-a" :clip/name "Beat A" :clip/start-tick 0 :clip/length-ticks 1920}]}
                  {:track/id "synth" :track/name "Synth" :track/color "#67e8f9" :track/gain 0.68
                   :track/clips [{:clip/id "chords" :clip/name "Chords" :clip/start-tick 960 :clip/length-ticks 2880}]}
                  {:track/id "voice" :track/name "Voice" :track/color "#c4b5fd" :track/gain 0.9
                   :track/clips [{:clip/id "hook" :clip/name "Hook" :clip/start-tick 2400 :clip/length-ticks 1440}]}]}))
(defonce state (r/atom {:project sample :playing? false :tick 1440}))
(defn track-row [track total]
  [:div.track-row [:div.track-head [:strong (:track/name track)]
    [:div.buttons [:button {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/mute? (not (:track/mute? track)))} (if (:track/mute? track) "M ✓" "M")]
     [:button {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/solo? (not (:track/solo? track)))} (if (:track/solo? track) "S ✓" "S")]]
    [:input {:type "range" :min 0 :max 1 :step .01 :value (:track/gain track)
             :aria-label (str (:track/name track) " gain")
             :on-change #(swap! state update :project daw/set-track (:track/id track) :track/gain (js/parseFloat (.. % -target -value)))}]]
   [:div.lane (for [clip (:track/clips track)] ^{:key (:clip/id clip)}
    [:button.clip {:style {:left (str (* 100 (/ (:clip/start-tick clip) total)) "%")
                           :width (str (* 100 (/ (:clip/length-ticks clip) total)) "%")
                           :background (:track/color track)}
                   :on-click #(swap! state assoc :tick (:clip/start-tick clip))} (:clip/name clip)])]])
(defn app [] (let [{:keys [project playing? tick]} @state total (max 3840 (daw/duration-ticks project))]
 [:main [:header [:div [:small "KOTOBA-LANG / MUSIC"] [:h1 "KAMI DAW"]]
   [:div.transport [:button.primary {:on-click #(swap! state update :playing? not)} (if playing? "❚❚ Pause" "▶ Play")]
    [:span (str "Tick " tick)] [:span (str (.toFixed (daw/tick->seconds project tick) 2) " s")]]]
  [:section.meta [:label "Project" [:input {:value (:project/name project) :on-change #(swap! state assoc-in [:project :project/name] (.. % -target -value))}]]
   [:label "Tempo" [:input {:type "number" :value (:project/bpm project) :on-change #(swap! state assoc-in [:project :project/bpm] (js/parseInt (.. % -target -value)))}]]
   [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy EDN"]]
  [:section.editor [:div.ruler [:span "1"] [:span "2"] [:span "3"] [:span "4"]]
   (for [track (:project/tracks project)] ^{:key (:track/id track)} [track-row track total])
   [:input.scrub {:type "range" :min 0 :max total :value tick :on-change #(swap! state assoc :tick (js/parseInt (.. % -target -value)))}]]
  [:footer (if-let [errors (seq (daw/validate-project project))] (str "Errors: " errors) "EDN project valid • browser prototype")]]))
(defonce root-node (atom nil))
(defn init! []
  (when-not @root-node
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app]))
