(ns kami.app-daw.ui (:require [reagent.core :as r] [reagent.dom.client :as rdom]
                              [kami.app-daw.core :as daw] [kami.app-daw.audio :as audio]))
(def sample (daw/project {:project/id "demo-song" :project/name "夜明けの波形"
 :project/tracks [{:track/id "drums" :track/name "Drums" :track/color "#ff8a65" :track/gain 0.82
                   :track/clips [{:clip/id "beat-a" :clip/name "Beat A" :clip/start-tick 0 :clip/length-ticks 1920}]}
                  {:track/id "synth" :track/name "Synth" :track/color "#67e8f9" :track/gain 0.68
                   :track/clips [{:clip/id "chords" :clip/name "Chords" :clip/start-tick 960 :clip/length-ticks 2880}]}
                  {:track/id "voice" :track/name "Voice" :track/color "#c4b5fd" :track/gain 0.9
                   :track/clips [{:clip/id "hook" :clip/name "Hook" :clip/start-tick 2400 :clip/length-ticks 1440}]}]}))
(defonce state (r/atom {:project sample :playing? false :tick 1440 :cutoff 4200 :delay 0.12 :exporting? false :buffers {} :assets {}}))
(defn import-track! [track event]
  (when-let [file (aget (.. event -target -files) 0)]
    (let [asset-id (str "audio:" (:track/id track))]
      (audio/decode-file! file
        (fn [buffer]
          (swap! state (fn [s]
                         (-> s (assoc-in [:buffers asset-id] buffer)
                             (assoc-in [:assets asset-id] {:name (.-name file) :waveform (audio/waveform buffer 48)})
                             (update :project daw/set-track (:track/id track) :track/clips
                                     (mapv #(assoc % :clip/asset-id asset-id) (:track/clips track)))))))))))
(defn toggle-play! []
  (if (:playing? @state)
    (do (audio/stop!) (swap! state assoc :playing? false))
    (do (audio/play! (:project @state) (:buffers @state) (select-keys @state [:cutoff :delay]))
        (swap! state assoc :playing? true))))
(defn export! []
  (swap! state assoc :exporting? true)
  (audio/export-wav! (:project @state) (:buffers @state) (select-keys @state [:cutoff :delay])
                     #(swap! state assoc :exporting? false)))
(defn track-row [track total]
  (let [asset-id (get-in track [:track/clips 0 :clip/asset-id]) asset (get-in @state [:assets asset-id])]
  [:div.track-row [:div.track-head [:strong (:track/name track)]
    [:div.buttons [:button {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/mute? (not (:track/mute? track)))} (if (:track/mute? track) "M ✓" "M")]
     [:button {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/solo? (not (:track/solo? track)))} (if (:track/solo? track) "S ✓" "S")]]
    [:input {:type "file" :accept "audio/*" :aria-label (str "Import " (:track/name track) " audio") :on-change #(import-track! track %)}]
    (when asset [:small (:name asset)])
    [:input {:type "range" :min 0 :max 1 :step .01 :value (:track/gain track)
             :aria-label (str (:track/name track) " gain")
             :on-change #(swap! state update :project daw/set-track (:track/id track) :track/gain (js/parseFloat (.. % -target -value)))}]]
   [:div.lane (when asset [:div.waveform {:style {:position "absolute" :inset "8px" :display "flex" :align-items "center" :gap "2px" :opacity .45}}
                              (for [[i peak] (map-indexed vector (:waveform asset))] ^{:key i} [:i {:style {:display "block" :flex 1 :min-height "2px" :height (str (* 90 peak) "%") :background "#dffcff"}}])])
    (for [clip (:track/clips track)] ^{:key (:clip/id clip)}
    [:button.clip {:style {:left (str (* 100 (/ (:clip/start-tick clip) total)) "%")
                           :width (str (* 100 (/ (:clip/length-ticks clip) total)) "%")
                           :background (:track/color track)}
                   :on-click #(swap! state assoc :tick (:clip/start-tick clip))} (:clip/name clip)])]]))
(defn app [] (let [{:keys [project playing? tick]} @state total (max 3840 (daw/duration-ticks project))]
 [:main [:header [:div [:small "KOTOBA-LANG / MUSIC"] [:h1 "KAMI DAW"]]
   [:div.transport [:button.primary {:on-click toggle-play!} (if playing? "■ Stop" "▶ Play audio")]
    [:span (str "Tick " tick)] [:span (str (.toFixed (daw/tick->seconds project tick) 2) " s")]]]
  [:section.meta [:label "Project" [:input {:value (:project/name project) :on-change #(swap! state assoc-in [:project :project/name] (.. % -target -value))}]]
   [:label "Tempo" [:input {:type "number" :value (:project/bpm project) :on-change #(swap! state assoc-in [:project :project/bpm] (js/parseInt (.. % -target -value)))}]]
   [:label "Low-pass" [:input {:type "range" :min 300 :max 12000 :step 100 :value (:cutoff @state) :on-change #(swap! state assoc :cutoff (js/parseFloat (.. % -target -value)))}]]
   [:label "Delay" [:input {:type "range" :min 0 :max .5 :step .01 :value (:delay @state) :on-change #(swap! state assoc :delay (js/parseFloat (.. % -target -value)))}]]
   [:button {:on-click export! :disabled (:exporting? @state)} (if (:exporting? @state) "Rendering…" "Export WAV")]
   [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy EDN"]]
  [:section.editor [:div.ruler [:span "1"] [:span "2"] [:span "3"] [:span "4"]]
   (for [track (:project/tracks project)] ^{:key (:track/id track)} [track-row track total])
   [:input.scrub {:type "range" :min 0 :max total :value tick :on-change #(swap! state assoc :tick (js/parseInt (.. % -target -value)))}]]
  [:footer (if-let [errors (seq (daw/validate-project project))] (str "Errors: " errors) "Web Audio playback • low-pass + delay effects • offline WAV master")]]))
(defonce root-node (atom nil))
(defn init! []
  (when-not @root-node
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app]))
