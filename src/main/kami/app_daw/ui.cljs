(ns kami.app-daw.ui (:require [reagent.core :as r] [reagent.dom.client :as rdom] [cljs.reader :as reader]
                              [kami.app-daw.core :as daw] [kami.app-daw.audio :as audio]
                              ["fflate" :refer [zipSync unzipSync strToU8 strFromU8]]))
(def sample (daw/project {:project/id "demo-song" :project/name "夜明けの波形"
 :project/tracks [{:track/id "drums" :track/name "Drums" :track/color "#ff8a65" :track/gain 0.82
                   :track/clips [{:clip/id "beat-a" :clip/name "Beat A" :clip/start-tick 0 :clip/length-ticks 1920}]}
                  {:track/id "synth" :track/name "Synth" :track/color "#67e8f9" :track/gain 0.68
                   :track/clips [{:clip/id "chords" :clip/name "Chords" :clip/start-tick 960 :clip/length-ticks 2880}]}
                  {:track/id "voice" :track/name "Voice" :track/color "#c4b5fd" :track/gain 0.9
                   :track/clips [{:clip/id "hook" :clip/name "Hook" :clip/start-tick 2400 :clip/length-ticks 1440}]}]}))
(defonce state (r/atom {:project sample :history daw/empty-history :history-replaying? false :playing? false :tick 1440 :selected "beat-a" :meter-db -96 :cutoff 4200 :delay 0.12 :exporting? false :stem-exporting nil :recording nil :recording-loop nil :recording-cancelled? false :input-monitoring? false :input-monitor-active? false :input-monitor-gain 0.35 :input-monitor-db -96 :recording-error nil :project-error nil :recovered? false :punch-length-ticks 960 :loop-takes 3 :buffers {} :assets {}}))
(defonce meter-timer (atom nil))
(defonce input-monitor-timer (atom nil))
(defonce recorder-runtime (atom nil))
(defonce shortcuts-installed? (atom false))
(def recovery-key "kami-app-daw/recovery/v1")
(defn sha256-file! [file]
  (-> (.arrayBuffer file)
      (.then #(.digest (.-subtle js/crypto) "SHA-256" %))
      (.then (fn [digest]
               (apply str (map #(.padStart (.toString % 16) 2 "0")
                               (array-seq (js/Uint8Array. digest))))))))
(defn start-meter! []
  (when @meter-timer (js/clearInterval @meter-timer))
  (reset! meter-timer (js/setInterval #(swap! state assoc :meter-db (audio/meter-db)) 80)))
(defn stop-meter! []
  (when @meter-timer (js/clearInterval @meter-timer) (reset! meter-timer nil))
  (swap! state assoc :meter-db -96))
(defn stop-input-monitor! []
  (when @input-monitor-timer (js/clearInterval @input-monitor-timer) (reset! input-monitor-timer nil))
  (audio/stop-input-monitor!)
  (swap! state assoc :input-monitor-active? false :input-monitor-db -96))
(defn start-input-monitor! [stream]
  (audio/start-input-monitor! stream (:input-monitor-gain @state))
  (when @input-monitor-timer (js/clearInterval @input-monitor-timer))
  (reset! input-monitor-timer (js/setInterval #(swap! state assoc :input-monitor-db (audio/input-monitor-db)) 80))
  (swap! state assoc :input-monitor-active? true))
(defn toggle-input-monitor! [enabled]
  (swap! state assoc :input-monitoring? enabled)
  (if enabled
    (when-let [stream (:stream @recorder-runtime)] (start-input-monitor! stream))
    (stop-input-monitor!)))
(defn set-input-monitor-gain! [gain]
  (let [level (daw/monitor-gain gain)]
    (swap! state assoc :input-monitor-gain level)
    (audio/set-input-monitor-gain! level)))
(defn import-track! [track event]
  (when-let [file (aget (.. event -target -files) 0)]
    (let [asset-id (str "audio:" (:track/id track))]
      (-> (sha256-file! file)
          (.then (fn [sha256]
                   (audio/decode-file! file
                     (fn [buffer]
                       (swap! state (fn [s]
                                      (-> s (assoc-in [:buffers asset-id] buffer)
                                          (assoc-in [:assets asset-id] {:name (.-name file) :type (.-type file) :sha256 sha256 :blob file :waveform (audio/waveform buffer 48)})
                                          (update :project daw/register-asset asset-id (.-name file) sha256)
                                          (update :project daw/set-track (:track/id track) :track/clips
                                                  (mapv #(assoc % :clip/asset-id asset-id) (:track/clips track))))))))))))))
(defn relink-audio! [event]
  (doseq [file (array-seq (.. event -target -files))]
    (-> (sha256-file! file)
        (.then (fn [sha256]
                 (when-let [asset-id (daw/asset-id-by-signature (:project @state) {:name (.-name file) :sha256 sha256})]
                   (audio/decode-file! file
                     (fn [buffer]
                       (swap! state (fn [s] (-> s (assoc-in [:buffers asset-id] buffer)
                                                 (assoc-in [:assets asset-id] {:name (.-name file) :type (.-type file) :sha256 sha256 :blob file :waveform (audio/waveform buffer 48)}))))))))))))
(declare record-loop-take!)
(defn finish-recording! [{:keys [track-id start-tick planned-sec chunks stream stop-timer comp-id take-index remaining]}]
  (when stop-timer (js/clearTimeout stop-timer))
  (stop-input-monitor!)
  (doseq [media-track (array-seq (.getTracks stream))] (.stop media-track))
  (let [blob (js/Blob. chunks #js {:type "audio/webm"})
        asset-id (str "recording:" comp-id ":" take-index)
        clip-id (str comp-id ":take-" take-index)]
    (-> (sha256-file! blob)
        (.then (fn [sha256]
                 (audio/decode-file!
                  blob
                  (fn [buffer]
                    (swap! state (fn [s]
                                   (-> s
                                       (assoc :recording-error nil)
                                       (assoc-in [:buffers asset-id] buffer)
                                       (assoc-in [:assets asset-id] {:name (str "Take " take-index ".webm") :type "audio/webm"
                                                                    :sha256 sha256 :blob blob :waveform (audio/waveform buffer 48)})
                                       (update :project daw/register-asset asset-id (str "Take " take-index ".webm") sha256)
                                       (update :project daw/add-comp-take track-id comp-id asset-id start-tick planned-sec clip-id take-index)
                                       (assoc :selected clip-id))))
                    (if (and (> remaining 1) (not (:recording-cancelled? @state)))
                      (record-loop-take! track-id comp-id start-tick planned-sec (inc take-index) (dec remaining))
                      (swap! state assoc :recording nil :recording-loop nil :recording-cancelled? false)))
                  (fn [error] (swap! state assoc :recording nil :recording-loop nil :recording-error (.-message error))))))
        (.catch #(swap! state assoc :recording nil :recording-loop nil :recording-error (.-message %))))))
(defn stop-recording! []
  (swap! state assoc :recording-cancelled? true)
  (when-let [recorder (:recorder @recorder-runtime)] (.stop recorder)))
(defn record-loop-take! [track-id comp-id start-tick planned-sec take-index remaining]
  (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
      (.then (fn [stream]
               (if (:recording-cancelled? @state)
                 (doseq [media-track (array-seq (.getTracks stream))] (.stop media-track))
                 (let [recorder (js/MediaRecorder. stream) chunks (array)
                       session (atom {:track-id track-id :start-tick start-tick :planned-sec planned-sec
                                      :comp-id comp-id :take-index take-index :remaining remaining
                                      :chunks chunks :stream stream :recorder recorder})]
                   (set! (.-ondataavailable recorder) #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
                   (set! (.-onstop recorder) #(do (reset! recorder-runtime nil) (finish-recording! @session)))
                   (reset! recorder-runtime @session)
                   (when (:input-monitoring? @state) (start-input-monitor! stream))
                   (swap! state assoc :recording track-id :recording-loop {:take take-index :total (+ take-index remaining -1)})
                   (.start recorder 100)
                   (let [timer (js/setTimeout #(.stop recorder) (* 1000 planned-sec))]
                     (swap! session assoc :stop-timer timer) (swap! recorder-runtime assoc :stop-timer timer))))))
      (.catch #(do (stop-input-monitor!) (swap! state assoc :recording nil :recording-loop nil :recording-error (.-message %))))))
(defn start-recording! [track-id]
  (let [comp-id (str "comp:" (.now js/Date))
        planned-sec (daw/punch-duration-seconds (:project @state) (:punch-length-ticks @state))
        take-count (max 1 (:loop-takes @state))]
    (swap! state assoc :recording-error nil :recording-cancelled? false)
    (record-loop-take! track-id comp-id (:tick @state) planned-sec 1 take-count)))
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
(defn download-file! [blob filename]
  (let [url (js/URL.createObjectURL blob) a (.createElement js/document "a")]
    (set! (.-href a) url) (set! (.-download a) filename) (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn export-package! []
  (let [project (:project @state) asset-ids (sort (keys (:project/assets project)))
        missing (vec (remove #(get-in @state [:assets % :blob]) asset-ids))
        total (reduce + 0 (keep #(some-> (get-in @state [:assets % :blob]) .-size) asset-ids))]
    (cond
      (seq missing) (swap! state assoc :project-error (str "Relink media before packaging: " (pr-str missing)))
      (> total daw/package-max-bytes) (swap! state assoc :project-error "Package media exceeds the 512 MiB browser limit")
      :else
      (-> (.all js/Promise
                (clj->js
                 (map-indexed
                  (fn [index asset-id]
                    (let [asset (get-in @state [:assets asset-id]) blob (:blob asset)]
                      (-> (.all js/Promise #js [(.arrayBuffer blob) (sha256-file! blob)])
                          (.then (fn [values]
                                   {:asset-id asset-id :entry-name (daw/package-entry-name index)
                                    :name (:name asset) :type (or (:type asset) (.-type blob) "application/octet-stream")
                                    :sha256 (aget values 1) :bytes (js/Uint8Array. (aget values 0))})))))
                  asset-ids)))
          (.then
           (fn [values]
             (let [items (array-seq values)
                   packaged-project (reduce (fn [p {:keys [asset-id sha256]}]
                                              (assoc-in p [:project/assets asset-id :asset/sha256] sha256)) project items)
                   media (into {} (map (fn [{:keys [asset-id entry-name name type sha256]}]
                                         [asset-id {:entry/name entry-name :media/name name :media/type type :media/sha256 sha256}]) items))
                   entries #js {}]
               (aset entries "project.edn" (strToU8 (pr-str packaged-project)))
               (aset entries "media.edn" (strToU8 (pr-str (daw/package-manifest packaged-project media))))
               (doseq [{:keys [entry-name bytes]} items] (aset entries entry-name bytes))
               (download-file! (js/Blob. #js [(zipSync entries #js {:level 0})] #js {:type "application/zip"})
                               "kami-daw-package.kami.zip")
               (swap! state assoc :project-error nil))))
          (.catch #(swap! state assoc :project-error (str "Package export failed: " (.-message %))))))))
(defn decode-blob! [blob]
  (js/Promise. (fn [resolve reject]
                 (try (audio/decode-file! blob resolve reject)
                      (catch :default error (reject error))))))
(defn unzip-package [array-buffer]
  (let [expanded-bytes (atom 0)]
    (unzipSync (js/Uint8Array. array-buffer)
               #js {:filter (fn [entry]
                              (swap! expanded-bytes + (.-originalSize entry))
                              (when (> @expanded-bytes daw/package-max-bytes)
                                (throw (js/Error. "Expanded package exceeds the 512 MiB browser limit")))
                              true)})))
(defn open-package! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (if (> (.-size file) daw/package-max-bytes)
      (swap! state assoc :project-error "Package exceeds the 512 MiB browser limit")
      (-> (.arrayBuffer file)
          (.then
           (fn [array-buffer]
             (let [entries (unzip-package array-buffer) names (set (js->clj (js/Object.keys entries)))
                   project-entry (aget entries "project.edn") manifest-entry (aget entries "media.edn")]
               (when-not (and project-entry manifest-entry) (throw (js/Error. "Package metadata is missing")))
               (let [project (reader/read-string (strFromU8 project-entry))
                     manifest (reader/read-string (strFromU8 manifest-entry))
                     accepted (daw/accept-package project manifest names)]
                 (when-not accepted (throw (js/Error. "Package contract is invalid")))
                 (-> (.all js/Promise
                           (clj->js
                            (map (fn [[asset-id descriptor]]
                                   (let [blob (js/Blob. #js [(aget entries (:entry/name descriptor))]
                                                        #js {:type (:media/type descriptor)})]
                                     (-> (sha256-file! blob)
                                         (.then (fn [sha256]
                                                  (when-not (= sha256 (:media/sha256 descriptor))
                                                    (throw (js/Error. (str "Media checksum mismatch: " asset-id))))
                                                  (-> (decode-blob! blob)
                                                      (.then (fn [buffer] {:asset-id asset-id :descriptor descriptor
                                                                          :blob blob :buffer buffer}))))))))
                                 (:media accepted))))
                     (.then (fn [values] {:project (:project accepted) :items (array-seq values)})))))))
          (.then
           (fn [{:keys [project items]}]
             (let [first-clip (first (mapcat :track/clips (:project/tracks project)))
                   buffers (into {} (map (juxt :asset-id :buffer) items))
                   assets (into {} (map (fn [{:keys [asset-id descriptor blob buffer]}]
                                          [asset-id {:name (:media/name descriptor) :type (:media/type descriptor)
                                                     :sha256 (:media/sha256 descriptor) :blob blob
                                                     :waveform (audio/waveform buffer 48)}]) items))]
               (audio/stop!) (stop-meter!)
               (swap! state assoc :project project :selected (:clip/id first-clip)
                      :tick (or (:clip/start-tick first-clip) 0) :playing? false :project-error nil
                      :buffers buffers :assets assets))))
          (.catch #(swap! state assoc :project-error (str "Package import failed: " (.-message %))))))))
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
      (if-let [{:keys [project history]} (daw/recover-workspace (reader/read-string text))]
        (let [first-clip (first (mapcat :track/clips (:project/tracks project)))]
          (swap! state assoc :project project :history history :selected (:clip/id first-clip)
                 :tick (or (:clip/start-tick first-clip) 0) :recovered? true :project-error nil))
        (do (.removeItem js/localStorage recovery-key)
            (swap! state assoc :project-error "Discarded invalid recovery data")))
      (catch :default _ (.removeItem js/localStorage recovery-key)))))
(defn install-autosave! []
  (add-watch state ::autosave
             (fn [_ _ old new]
               (when (or (not= (:project old) (:project new)) (not= (:history old) (:history new)))
                 (js/queueMicrotask
                  (fn []
                    (try (.setItem js/localStorage recovery-key
                                   (pr-str (daw/recovery-envelope (:project @state) (:history @state))))
                         (catch :default error (js/console.warn "DAW autosave failed" error)))))))))
(defn install-history! []
  (add-watch state ::history
             (fn [_ _ old new]
               (when (and (not= (:project old) (:project new)) (not (:history-replaying? new)))
                 (swap! state update :history daw/record-history (:project old))))))
(defn undo! []
  (let [{:keys [project history]} (daw/undo-project (:project @state) (:history @state))]
    (swap! state assoc :project project :history history :history-replaying? true)
    (swap! state assoc :history-replaying? false)))
(defn redo! []
  (let [{:keys [project history]} (daw/redo-project (:project @state) (:history @state))]
    (swap! state assoc :project project :history history :history-replaying? true)
    (swap! state assoc :history-replaying? false)))
(defn install-shortcuts! []
  (when-not @shortcuts-installed?
    (.addEventListener js/window "keydown"
      (fn [event]
        (when (and (or (.-metaKey event) (.-ctrlKey event)) (= "z" (.toLowerCase (.-key event))))
          (.preventDefault event) (if (.-shiftKey event) (redo!) (undo!)))))
    (.addEventListener js/window "beforeunload" (fn [_] (stop-input-monitor!)))
    (reset! shortcuts-installed? true)))
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
    (for [group (:track/take-lanes track)]
      ^{:key (:comp/id group)}
      [:div.comp-lane [:small (str "Comp • " (count (:comp/takes group)) " takes")]
       (for [take (:comp/takes group)]
         ^{:key (:clip/id take)}
         [:button {:aria-label (str "Select " (:track/name track) " comp take " (:clip/take-index take))
                   :class (when (= (:clip/id take) (:comp/active-take-id group)) "selected")
                   :on-click #(swap! state update :project daw/select-comp-take (:track/id track) (:comp/id group) (:clip/id take))}
          (str "T" (:clip/take-index take))])])
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
(defn app [] (let [{:keys [project playing? tick]} @state total (max 3840 (daw/duration-ticks project))
                    missing (daw/missing-asset-ids project (keys (:buffers @state)))]
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
   [:label "Loop takes" [:input {:type "number" :min 1 :max 8 :value (:loop-takes @state) :aria-label "Loop take count"
                                  :on-change #(swap! state assoc :loop-takes (max 1 (min 8 (js/parseInt (.. % -target -value)))))}]]
   [:label "Input monitor (use headphones)" [:input {:type "checkbox" :checked (:input-monitoring? @state)
                                                       :aria-label "Enable input monitoring"
                                                       :on-click #(toggle-input-monitor! (not (:input-monitoring? @state)))}]]
   [:label "Monitor gain" [:input {:type "range" :min 0 :max 1 :step 0.05 :value (:input-monitor-gain @state)
                                    :aria-label "Input monitor gain"
                                    :on-change #(set-input-monitor-gain! (js/parseFloat (.. % -target -value)))}]]
   [:meter {:min -60 :max 0 :value (max -60 (:input-monitor-db @state))
            :title (str "Input " (.toFixed (:input-monitor-db @state) 1) " dBFS")}]
   [:button {:on-click export! :disabled (:exporting? @state)} (if (:exporting? @state) "Rendering…" "Export WAV")]
   [:button {:on-click download-project!} "Save project EDN"]
   [:label "Open project EDN" [:input {:type "file" :accept ".edn,application/edn" :aria-label "Open DAW project EDN" :on-change load-project!}]]
   [:button {:on-click export-package!} "Package project + media"]
   [:label "Open media package" [:input {:type "file" :accept ".zip,.kami.zip,application/zip" :aria-label "Open DAW media package" :on-change open-package!}]]
   [:label "Relink audio" [:input {:type "file" :accept "audio/*" :multiple true :aria-label "Relink DAW audio files" :on-change relink-audio!}]]
   [:button {:on-click undo! :disabled (empty? (get-in @state [:history :history/past])) :aria-label "Undo project edit"} "↶ Undo"]
   [:button {:on-click redo! :disabled (empty? (get-in @state [:history :history/future])) :aria-label "Redo project edit"} "↷ Redo"]
   [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy EDN"]]
  (when-let [error (:project-error @state)] [:section.meta [:strong (str "Project error: " error)]])
  (when (:recovered? @state) [:section.meta [:strong "Recovered autosaved project"]])
  (when (seq missing) [:section.meta.missing-media [:strong (str "Missing media: " (count missing))] [:span (pr-str missing)]])
  (when-let [error (:recording-error @state)] [:section.meta [:strong (str "Recording error: " error)]])
  (when-let [{:keys [take total]} (:recording-loop @state)] [:section.meta [:strong (str "Capturing loop take " take " / " total)]])
  (when (:input-monitor-active? @state) [:section.meta [:strong "Input monitor active • headphones recommended"]])
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
    (restore-recovery!) (install-history!) (install-autosave!) (install-shortcuts!)
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app]))
