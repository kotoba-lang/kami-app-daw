(ns kami.app-daw.ui (:require [reagent.core :as r] [reagent.dom.client :as rdom] [cljs.reader :as reader]
                              [kami.app-daw.core :as daw] [kami.app-daw.audio :as audio]))
(def sample (daw/project {:project/id "demo-song" :project/name "夜明けの波形"
 :project/tracks [{:track/id "drums" :track/name "Drums" :track/color "#ff8a65" :track/gain 0.82
                   :track/clips [{:clip/id "beat-a" :clip/name "Beat A" :clip/start-tick 0 :clip/length-ticks 1920}]}
                  {:track/id "synth" :track/name "Synth" :track/color "#67e8f9" :track/gain 0.68
                   :track/clips [{:clip/id "chords" :clip/name "Chords" :clip/start-tick 960 :clip/length-ticks 2880}]}
                  {:track/id "voice" :track/name "Voice" :track/color "#c4b5fd" :track/gain 0.9
                   :track/clips [{:clip/id "hook" :clip/name "Hook" :clip/start-tick 2400 :clip/length-ticks 1440}]}]}))
(defonce state (r/atom {:project sample :playing? false :tick 1440 :selected "beat-a" :meter-db -96 :cutoff 4200 :delay 0.12 :exporting? false :stem-exporting nil :recording nil :recording-error nil :project-error nil :recovered? false :punch-length-ticks 960 :buffers {} :assets {}}))
(defonce meter-timer (atom nil))
(defonce recorder-runtime (atom nil))
(def recovery-key "kami-app-daw/recovery/v1")
(defn start-meter! []
  (when @meter-timer (js/clearInterval @meter-timer))
  (reset! meter-timer (js/setInterval #(swap! state assoc :meter-db (audio/meter-db)) 80)))
(defn stop-meter! []
  (when @meter-timer (js/clearInterval @meter-timer) (reset! meter-timer nil))
  (swap! state assoc :meter-db -96))
(defn import-track! [track event]
  (when-let [file (aget (.. event -target -files) 0)]
    (let [asset-id (str "audio:" (:track/id track))]
      (audio/decode-file! file
        (fn [buffer]
          (swap! state (fn [s]
                         (-> s (assoc-in [:buffers asset-id] buffer)
                             (assoc-in [:assets asset-id] {:name (.-name file) :waveform (audio/waveform buffer 48)})
                             (update :project daw/register-asset asset-id (.-name file))
                             (update :project daw/set-track (:track/id track) :track/clips
                                     (mapv #(assoc % :clip/asset-id asset-id) (:track/clips track)))))))))))
(defn relink-audio! [event]
  (doseq [file (array-seq (.. event -target -files))]
    (when-let [asset-id (daw/asset-id-by-name (:project @state) (.-name file))]
      (audio/decode-file! file
        (fn [buffer]
          (swap! state (fn [s] (-> s (assoc-in [:buffers asset-id] buffer)
                                    (assoc-in [:assets asset-id] {:name (.-name file) :waveform (audio/waveform buffer 48)})))))))))
(defn finish-recording! [{:keys [track-id start-tick planned-sec chunks stream stop-timer]}]
  (when stop-timer (js/clearTimeout stop-timer))
  (doseq [media-track (array-seq (.getTracks stream))] (.stop media-track))
  (let [blob (js/Blob. chunks #js {:type "audio/webm"})
        asset-id (str "recording:" (.now js/Date))
        clip-id (str "take-" (.now js/Date))]
    (audio/decode-file! blob
      (fn [buffer]
        (let [duration planned-sec]
          (swap! state (fn [s]
                         (-> s (assoc :recording nil :recording-error nil)
                             (assoc-in [:buffers asset-id] buffer)
                             (assoc-in [:assets asset-id] {:name "Recorded take" :waveform (audio/waveform buffer 48)})
                             (update :project daw/register-asset asset-id "Recorded take")
                             (update :project daw/add-recorded-clip track-id asset-id start-tick duration clip-id)
                             (assoc :selected clip-id)))))))))
(defn stop-recording! []
  (when-let [recorder (:recorder @recorder-runtime)] (.stop recorder)))
(defn start-recording! [track-id]
  (swap! state assoc :recording-error nil)
  (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
      (.then (fn [stream]
               (let [recorder (js/MediaRecorder. stream) chunks (array)
                     planned-sec (daw/punch-duration-seconds (:project @state) (:punch-length-ticks @state))
                     session (atom {:track-id track-id :start-tick (:tick @state) :planned-sec planned-sec
                                    :chunks chunks :stream stream :recorder recorder})]
                 (set! (.-ondataavailable recorder) #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
                 (set! (.-onstop recorder) #(do (reset! recorder-runtime nil) (finish-recording! @session)))
                 (reset! recorder-runtime @session) (swap! state assoc :recording track-id) (.start recorder 100)
                 (let [timer (js/setTimeout #(.stop recorder) (* 1000 planned-sec))]
                   (swap! session assoc :stop-timer timer) (swap! recorder-runtime assoc :stop-timer timer)))))
      (.catch #(swap! state assoc :recording nil :recording-error (.-message %)))))
(defn toggle-play! []
  (if (:playing? @state)
    (do (audio/stop!) (stop-meter!) (swap! state assoc :playing? false))
    (do (audio/play! (:project @state) (:buffers @state) (select-keys @state [:cutoff :delay]))
        (start-meter!) (swap! state assoc :playing? true))))
(defn export! []
  (swap! state assoc :exporting? true)
  (audio/export-wav! (:project @state) (:buffers @state) (select-keys @state [:cutoff :delay])
                     #(swap! state assoc :exporting? false)))
(defn export-stem! [track-id]
  (swap! state assoc :stem-exporting track-id)
  (audio/export-track-wav! (:project @state) track-id (:buffers @state) (select-keys @state [:cutoff :delay])
                           #(swap! state assoc :stem-exporting nil)))
(defn download-project! []
  (let [blob (js/Blob. #js [(pr-str (:project @state))] #js {:type "application/edn"})
        url (js/URL.createObjectURL blob) a (.createElement js/document "a")]
    (set! (.-href a) url) (set! (.-download a) "kami-daw-project.edn") (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn load-project! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (-> (.text file)
        (.then (fn [text]
                 (try
                   (if-let [project (daw/accept-project (reader/read-string text))]
                     (let [first-clip (first (mapcat :track/clips (:project/tracks project)))]
                       (audio/stop!) (stop-meter!)
                       (swap! state assoc :project project :selected (:clip/id first-clip)
                              :tick (or (:clip/start-tick first-clip) 0) :playing? false :project-error nil
                              :buffers {} :assets {}))
                     (swap! state assoc :project-error "Unsupported or invalid DAW project"))
                   (catch :default error (swap! state assoc :project-error (.-message error)))))))))
(defn restore-recovery! []
  (when-let [text (.getItem js/localStorage recovery-key)]
    (try
      (if-let [project (daw/recover-project (reader/read-string text))]
        (let [first-clip (first (mapcat :track/clips (:project/tracks project)))]
          (swap! state assoc :project project :selected (:clip/id first-clip)
                 :tick (or (:clip/start-tick first-clip) 0) :recovered? true :project-error nil))
        (do (.removeItem js/localStorage recovery-key)
            (swap! state assoc :project-error "Discarded invalid recovery data")))
      (catch :default _ (.removeItem js/localStorage recovery-key)))))
(defn install-autosave! []
  (add-watch state ::autosave
             (fn [_ _ old new]
               (when (not= (:project old) (:project new))
                 (try (.setItem js/localStorage recovery-key (pr-str (daw/recovery-envelope (:project new))))
                      (catch :default error (js/console.warn "DAW autosave failed" error)))))))
(defn set-automation! [track endpoint gain]
  (let [end-tick (daw/duration-ticks (:project @state))
        current (or (:track/gain-automation track)
                    [{:automation/tick 0 :automation/gain (or (:track/gain track) 1)}
                     {:automation/tick end-tick :automation/gain (or (:track/gain track) 1)}])
        points (mapv (fn [point] (if (= endpoint (:automation/tick point))
                                   {:tick endpoint :gain gain}
                                   {:tick (:automation/tick point) :gain (:automation/gain point)})) current)]
    (swap! state update :project daw/set-gain-automation (:track/id track) points)))
(defn track-row [track total]
  (let [asset-id (get-in track [:track/clips 0 :clip/asset-id]) asset (get-in @state [:assets asset-id])]
  [:div.track-row [:div.track-head [:strong (:track/name track)]
    [:div.buttons [:button {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/mute? (not (:track/mute? track)))} (if (:track/mute? track) "M ✓" "M")]
     [:button {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/solo? (not (:track/solo? track)))} (if (:track/solo? track) "S ✓" "S")]]
    [:input {:type "file" :accept "audio/*" :aria-label (str "Import " (:track/name track) " audio") :on-change #(import-track! track %)}]
    (when asset [:small (:name asset)])
    [:button {:aria-label (str (if (= (:track/id track) (:recording @state)) "Stop " "Record ") (:track/name track))
              :disabled (and (:recording @state) (not= (:track/id track) (:recording @state)))
              :on-click #(if (= (:track/id track) (:recording @state)) (stop-recording!) (start-recording! (:track/id track)))}
     (if (= (:track/id track) (:recording @state)) "■ Stop take" "● Record")]
    (let [end-tick (daw/duration-ticks (:project @state)) points (:track/gain-automation track)
          start-gain (or (:automation/gain (first points)) (:track/gain track) 1)
          end-gain (or (:automation/gain (last points)) (:track/gain track) 1)]
      [:div.buttons
       [:label "A→" [:input {:type "number" :min 0 :max 2 :step 0.05 :value start-gain :aria-label (str (:track/name track) " automation start") :on-change #(set-automation! track 0 (js/parseFloat (.. % -target -value)))}]]
       [:label "→B" [:input {:type "number" :min 0 :max 2 :step 0.05 :value end-gain :aria-label (str (:track/name track) " automation end") :on-change #(set-automation! track end-tick (js/parseFloat (.. % -target -value)))}]]])
    [:label "Send" [:input {:type "range" :min 0 :max 1 :step 0.05 :value (or (:track/send track) 0)
                             :aria-label (str (:track/name track) " delay send")
                             :on-change #(swap! state update :project daw/route-track (:track/id track) "master" (js/parseFloat (.. % -target -value)))}]]
    [:button {:on-click #(export-stem! (:track/id track)) :disabled (= (:track/id track) (:stem-exporting @state))}
     (if (= (:track/id track) (:stem-exporting @state)) "Rendering stem…" "Export stem")]
    [:input {:type "range" :min 0 :max 1 :step 0.01 :value (:track/gain track)
             :aria-label (str (:track/name track) " gain")
             :on-change #(swap! state update :project daw/set-track (:track/id track) :track/gain (js/parseFloat (.. % -target -value)))}]]
   [:div.lane (when asset [:div.waveform {:style {:position "absolute" :inset "8px" :display "flex" :align-items "center" :gap "2px" :opacity 0.45}}
                              (for [[i peak] (map-indexed vector (:waveform asset))] ^{:key i} [:i {:style {:display "block" :flex 1 :min-height "2px" :height (str (* 90 peak) "%") :background "#dffcff"}}])])
    (for [clip (:track/clips track)] ^{:key (:clip/id clip)}
    [:button.clip {:style {:left (str (* 100 (/ (:clip/start-tick clip) total)) "%")
                           :width (str (* 100 (/ (:clip/length-ticks clip) total)) "%")
                           :background (:track/color track)}
                   :on-click #(swap! state assoc :tick (:clip/start-tick clip) :selected (:clip/id clip))} (:clip/name clip)])]]))
(defn selected-clip [project id] (some #(when (= id (:clip/id %)) %) (mapcat :track/clips (:project/tracks project))))
(defn edit-selected! [k value]
  (let [id (:selected @state) clip (selected-clip (:project @state) id)
        edit {:source-offset-sec (or (:clip/source-offset-sec clip) 0)
              :fade-in-sec (or (:clip/fade-in-sec clip) 0.02)
              :fade-out-sec (or (:clip/fade-out-sec clip) 0.05)}]
    (swap! state update :project daw/edit-clip id (assoc edit k value))))
(defn app [] (let [{:keys [project playing? tick]} @state total (max 3840 (daw/duration-ticks project))]
 [:main [:header [:div [:small "KOTOBA-LANG / MUSIC"] [:h1 "KAMI DAW"]]
   [:div.transport [:button.primary {:on-click toggle-play!} (if playing? "■ Stop" "▶ Play audio")]
    [:span (str "Tick " tick)] [:span (str (.toFixed (daw/tick->seconds project tick) 2) " s")]
    [:meter {:min -60 :max 0 :value (max -60 (:meter-db @state)) :title (str (.toFixed (:meter-db @state) 1) " dBFS")}]]]
  [:section.meta [:label "Project" [:input {:value (:project/name project) :on-change #(swap! state assoc-in [:project :project/name] (.. % -target -value))}]]
   [:label "Tempo" [:input {:type "number" :value (:project/bpm project) :on-change #(swap! state assoc-in [:project :project/bpm] (js/parseInt (.. % -target -value)))}]]
   [:label "Low-pass" [:input {:type "range" :min 300 :max 12000 :step 100 :value (:cutoff @state) :on-change #(swap! state assoc :cutoff (js/parseFloat (.. % -target -value)))}]]
   [:label "Delay" [:input {:type "range" :min 0 :max 0.5 :step 0.01 :value (:delay @state) :on-change #(swap! state assoc :delay (js/parseFloat (.. % -target -value)))}]]
   [:label "Punch ticks" [:input {:type "number" :min 1 :step 120 :value (:punch-length-ticks @state) :aria-label "Punch length ticks"
                                   :on-change #(swap! state assoc :punch-length-ticks (max 1 (js/parseInt (.. % -target -value))))}]]
   [:button {:on-click export! :disabled (:exporting? @state)} (if (:exporting? @state) "Rendering…" "Export WAV")]
   [:button {:on-click download-project!} "Save project EDN"]
   [:label "Open project EDN" [:input {:type "file" :accept ".edn,application/edn" :aria-label "Open DAW project EDN" :on-change load-project!}]]
   [:label "Relink audio" [:input {:type "file" :accept "audio/*" :multiple true :aria-label "Relink DAW audio files" :on-change relink-audio!}]]
   [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy EDN"]]
  (when-let [error (:project-error @state)] [:section.meta [:strong (str "Project error: " error)]])
  (when (:recovered? @state) [:section.meta [:strong "Recovered autosaved project"]])
  (when-let [error (:recording-error @state)] [:section.meta [:strong (str "Recording error: " error)]])
  (when-let [clip (selected-clip project (:selected @state))]
    [:section.meta.clip-editor [:strong (str "Edit • " (:clip/name clip))]
     [:label "Source offset" [:input {:type "number" :min 0 :step 0.05 :value (or (:clip/source-offset-sec clip) 0) :on-change #(edit-selected! :source-offset-sec (js/parseFloat (.. % -target -value)))}]]
     [:label "Fade in" [:input {:type "number" :min 0 :step 0.05 :value (or (:clip/fade-in-sec clip) 0.02) :on-change #(edit-selected! :fade-in-sec (js/parseFloat (.. % -target -value)))}]]
     [:label "Fade out" [:input {:type "number" :min 0 :step 0.05 :value (or (:clip/fade-out-sec clip) 0.05) :on-change #(edit-selected! :fade-out-sec (js/parseFloat (.. % -target -value)))}]]])
  [:section.editor [:div.ruler [:span "1"] [:span "2"] [:span "3"] [:span "4"]]
   (for [track (:project/tracks project)] ^{:key (:track/id track)} [track-row track total])
   [:input.scrub {:type "range" :min 0 :max total :value tick :on-change #(swap! state assoc :tick (js/parseInt (.. % -target -value)))}]]
  [:footer (if-let [errors (seq (daw/validate-project project))] (str "Errors: " errors) "Web Audio playback • low-pass + delay effects • offline WAV master")]]))
(defonce root-node (atom nil))
(defn init! []
  (when-not @root-node
    (restore-recovery!) (install-autosave!)
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app]))
