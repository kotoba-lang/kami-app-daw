(ns kami.app-daw.ui (:require [reagent.core :as r] [reagent.dom.client :as rdom] [cljs.reader :as reader]
                              [kami.app-daw.core :as daw] [kami.app-daw.audio :as audio] [kami.app-daw.bench :as bench]
                              [html.core :as html]
                              [jp-go-dds.core :as dds]
                              [kami.app-daw.theme :as theme]
                              [kami.app-daw.asset-sources :as asset-sources]
                              ["fflate" :refer [zipSync unzipSync strToU8 strFromU8]]))
(def sample (daw/project {:project/id "demo-song" :project/name "夜明けの波形"
 :project/tracks [{:track/id "drums" :track/name "Drums" :track/color (theme/track-color 0) :track/gain 0.82
                   :track/clips [{:clip/id "beat-a" :clip/name "Beat A" :clip/start-tick 0 :clip/length-ticks 1920}]}
                  {:track/id "synth" :track/name "Synth" :track/color (theme/track-color 1) :track/gain 0.68
                   :track/clips [{:clip/id "chords" :clip/name "Chords" :clip/start-tick 960 :clip/length-ticks 2880}]}
                  {:track/id "voice" :track/name "Voice" :track/color (theme/track-color 2) :track/gain 0.9
 :track/clips [{:clip/id "hook" :clip/name "Hook" :clip/start-tick 2400 :clip/length-ticks 1440}]}]}))
(def kotoba-html-contract
  (html/html [:meta {:name "kotoba:app-shell" :content "kami-daw single-screen dads"}]
             [:noscript "KAMI DAW requires JavaScript for audio transport and rendering."]))
(defonce state (r/atom {:project sample :history daw/empty-history :history-replaying? false :clip-drag nil :clip-preview nil :playing? false :tick 1440 :selected "beat-a" :meter-db -96 :cutoff 4200 :delay 0.12 :exporting? false :analyzing? false :loudness-report nil :normalize-export? true :target-lufs -14 :true-peak-ceiling-db -1 :stem-exporting nil :stem-bundle-exporting? false :directory-searching? false :directory-result nil :recording nil :recording-loop nil :recording-cancelled? false :input-monitoring? false :input-monitor-active? false :input-monitor-gain 0.35 :input-monitor-db -96 :recording-error nil :plugin-package-status nil :project-error nil :recovered? false :punch-length-ticks 960 :loop-takes 3 :mackie-bank 0 :mackie-profile :auto :mackie-active-profile :generic-mcu :mackie-touched-strips #{} :buffers {} :assets {} :network-source-status "Not loaded" :network-sources []}))
(defonce bench-run (r/atom (or (bench/restore) (bench/initial-run))))
(defn bench-panel [] (let [run @bench-run actor (bench/actor run)] [:aside.bench-panel {:aria-label "User test actor loop"} [:strong "User test loop"] [:select {:value (:actor-id run) :on-change #(do (swap! bench-run assoc :actor-id (.. % -target -value) :task-index 0) (bench/persist! @bench-run))} (for [{:keys [id label kind]} bench/actors] ^{:key id} [:option {:value id} (str label " • " (name kind))])] [:p (str "Task " (inc (:task-index run)) "/" (count (:tasks actor)) ": " (bench/current-task run))] (dds/button "Log observation" {:type :outline :size "sm" :attrs {:on-click #(bench/record! bench-run :observation {:note "manual observation"})}}) (dds/button "Pass / next task" {:type :outline :size "sm" :attrs {:on-click #(bench/complete-task! bench-run true 0)}}) (dds/button "Fail / next task" {:type :outline :size "sm" :attrs {:on-click #(bench/complete-task! bench-run false 1)}}) [:textarea {:placeholder "Participant feedback" :aria-label "Participant feedback" :on-blur #(bench/feedback! bench-run (.. % -target -value) :friction 0)}] (dds/button "Export EDN" {:type :outline :size "sm" :attrs {:on-click #(bench/export! @bench-run)}}) [:small (str "events=" (count (:events run)) " • session=" (:session-id run))]]))
(defonce meter-timer (atom nil))
(defonce pending-plugin-package (r/atom nil))
(defonce publisher-rotation-drafts (r/atom {}))
(defonce transport-timer (atom nil))
(defonce transport-origin (atom nil))
(defonce input-monitor-timer (atom nil))
(defonce recorder-runtime (atom nil))
(defonce midi-access-runtime (atom nil))
(defonce mackie-time-slot (atom nil))
(defonce shortcuts-installed? (atom false))
(declare stop-meter! start-playback! stop-playback! sha256-file!)
(declare import-track!)
(defn load-network-sources! []
  (swap! state assoc :network-source-status "Loading network-isekai and kotobase.net…")
  (-> (asset-sources/load-all!)
      (.then (fn [sources] (swap! state assoc :network-sources (vec (array-seq sources))
                                   :network-source-status (str "Connected: " (count (array-seq sources)) " sources"))))
      (.catch #(swap! state assoc :network-source-status (str "Partial/offline: " (.-message %))))))
(defn import-remote-audio! [track item]
  (-> (js/fetch (:asset/uri item))
      (.then (fn [r] (if (.-ok r) (.blob r) (throw (js/Error. (str "asset HTTP " (.-status r)))))))
      (.then (fn [blob] (let [file (js/File. #js [blob] (or (:asset/name item) "network-asset") #js {:type (or (:asset/mime item) (.-type blob) "audio/wav")})]
                          (import-track! track #js {:target #js {:files #js [file]}}))))
      (.catch #(swap! state assoc :project-error (str "Remote audio import failed: " (.-message %))))))
(defn plugin-package [raw]
  (let [parameters (into {}
                         (map (fn [[parameter descriptor]]
                                [(keyword parameter)
                                 {:parameter/name (get descriptor "name")
                                  :parameter/min (get descriptor "min")
                                  :parameter/max (get descriptor "max")
                                  :parameter/default (get descriptor "default")
                                  :parameter/step (get descriptor "step")}]))
                         (get raw "parameters"))]
    {:package/version (get raw "version") :package/id (get raw "id")
     :package/source (get raw "source")
     :package/source-sha256 (get raw "sourceSha256")
     :package/capabilities (set (map keyword (get raw "capabilities")))
     :package/publisher-id (get-in raw ["publisher" "id"])
     :package/publisher-name (get-in raw ["publisher" "name"])
     :package/publisher-key (get-in raw ["publisher" "publicKey"])
     :package/publisher-fingerprint (get-in raw ["publisher" "fingerprint"])
     :package/signature (get raw "signature")
     :package/descriptor {:plugin/name (get raw "name") :plugin/processor (get raw "processor")
                          :plugin/parameters parameters}}))
(defn base64-bytes [value]
  (let [binary (js/atob value) bytes (js/Uint8Array. (count binary))]
    (doseq [index (range (count binary))]
      (aset bytes index (.charCodeAt binary index)))
    bytes))
(defn sha256-text! [value]
  (-> (.digest (.-subtle js/crypto) "SHA-256" (.encode (js/TextEncoder.) value))
      (.then (fn [buffer]
               (apply str (map #(let [hex (.toString % 16)]
                                  (if (= 1 (count hex)) (str "0" hex) hex))
                               (array-seq (js/Uint8Array. buffer))))))))
(defn verify-plugin-publisher! [package]
  (let [key-json (js/JSON.stringify (clj->js (:package/publisher-key package)))]
    (-> (js/Promise.all
         #js [(.importKey (.-subtle js/crypto) "jwk" (clj->js (:package/publisher-key package))
                          #js {:name "ECDSA" :namedCurve "P-256"} false #js ["verify"])
              (sha256-text! key-json)])
        (.then (fn [values]
                 (let [key (aget values 0) fingerprint (aget values 1)]
                   (if (not= fingerprint (:package/publisher-fingerprint package))
                     false
                     (.verify (.-subtle js/crypto) #js {:name "ECDSA" :hash "SHA-256"} key
                              (base64-bytes (:package/signature package))
                              (.encode (js/TextEncoder.) (:package/source-sha256 package)))))))
        (.catch (fn [_] false)))))
(defn verify-revocation-list! [raw]
  (let [publisher-id (get raw "publisherId") payload (get raw "signedPayload")
        public-key (get raw "publisherKey") signature (get raw "signature")
        trusted-fingerprint (get-in @state [:project :project/trusted-publishers publisher-id :publisher/fingerprint])
        key-json (js/JSON.stringify (clj->js public-key))]
    (-> (js/Promise.all
         #js [(.importKey (.-subtle js/crypto) "jwk" (clj->js public-key)
                          #js {:name "ECDSA" :namedCurve "P-256"} false #js ["verify"])
              (sha256-text! key-json)])
        (.then (fn [values]
                 (if (not= trusted-fingerprint (aget values 1)) false
                     (-> (.verify (.-subtle js/crypto) #js {:name "ECDSA" :hash "SHA-256"} (aget values 0)
                                  (base64-bytes signature) (.encode (js/TextEncoder.) payload))
                         (.then (fn [valid?]
                                  (when valid?
                                    (let [body (js->clj (js/JSON.parse payload))]
                                      (when (= publisher-id (get body "publisherId"))
                                        {:publisher-id publisher-id
                                         :list {:revocation/version (get body "version")
                                                :revocation/issued-at (get body "issuedAt")
                                                :revocation/next-update (get body "nextUpdate")
                                                :revocation/fingerprints (vec (get body "fingerprints"))
                                                :revocation/signature signature}})))))))))
        (.catch (fn [_] false)))))
(defn import-revocation-list! [file]
  (when file
    (-> (.text file)
        (.then #(verify-revocation-list! (js->clj (js/JSON.parse %))))
        (.then (fn [verified]
                 (if-not verified
                   (swap! state assoc :revocation-status "Revocation list rejected: signature, key, or publisher mismatch")
                   (let [before (get-in @state [:project :project/revocation-lists (:publisher-id verified) :revocation/version] 0)]
                     (swap! state update :project daw/apply-revocation-list (:publisher-id verified) (:list verified)
                            (long (/ (.now js/Date) 1000)))
                     (let [after (get-in @state [:project :project/revocation-lists (:publisher-id verified) :revocation/version] 0)]
                       (swap! state assoc :revocation-status
                              (if (> after before) (str "Verified revocation list v" after)
                                  "Revocation list rejected: stale, rollback, or expired")))))))
        (.catch #(swap! state assoc :revocation-status (str "Revocation list error: " (.-message %)))))))
(defn install-plugin-package! [package]
  (let [plugin-id (str (:package/id package) "-" (inc (count (get-in @state [:project :project/plugins]))))]
    (swap! state update :project daw/add-third-party-plugin plugin-id package)
    (reset! pending-plugin-package nil)
    (swap! state assoc :plugin-package-status (str "Verified signed package " (:package/id package)))))
(defn trust-pending-plugin-publisher! []
  (when-let [package @pending-plugin-package]
    (swap! state update :project daw/trust-plugin-publisher
           (:package/publisher-id package) (:package/publisher-fingerprint package)
           (:package/publisher-name package))
    (install-plugin-package! package)))
(defn import-plugin-package! [file]
  (when file
    (-> (.text file)
        (.then (fn [text]
                 (let [package (plugin-package (js->clj (js/JSON.parse text)))]
                   (-> (sha256-file! (js/Blob. #js [(:package/source package)]))
                       (.then (fn [actual]
                                (let [errors (daw/third-party-plugin-package-errors package)]
                                  (cond
                                    (seq errors) (swap! state assoc :plugin-package-status (str "Plugin rejected: " errors))
                                    (not= actual (:package/source-sha256 package))
                                    (swap! state assoc :plugin-package-status "Plugin rejected: source integrity mismatch")
                                    :else
                                    (-> (verify-plugin-publisher! package)
                                        (.then (fn [signature-valid?]
                                                 (cond
                                                   (not signature-valid?)
                                                   (swap! state assoc :plugin-package-status "Plugin rejected: invalid publisher signature")
                                                   (daw/trusted-plugin-package? (:project @state) package)
                                                   (install-plugin-package! package)
                                                   :else
                                                   (do (reset! pending-plugin-package package)
                                                       (swap! state assoc :plugin-package-status
                                                              (str "Signed package awaits trust: "
                                                                   (:package/publisher-name package))))))))))))))))
        (.catch #(swap! state assoc :plugin-package-status (str "Plugin package error: " (.-message %)))))))
(def recovery-key "kami-app-daw/recovery/v1")
(defn send-midi-feedback! [plugin-id value]
  (when-let [access @midi-access-runtime]
    (doseq [mapping (filter #(= plugin-id (:target/plugin-id %))
                            (get-in @state [:project :project/midi-mappings]))
            output (array-seq (js/Array.from (.values (or (.-outputs access) (js/Map.)))))]
      (.send output (clj->js (daw/midi-feedback-message mapping value))))))
(defn send-midi-transport-feedback! [command]
  (when-let [message (daw/midi-transport-message command)]
    (when-let [access @midi-access-runtime]
      (doseq [output (array-seq (js/Array.from (.values (or (.-outputs access) (js/Map.)))))]
        (.send output (clj->js message))))))
(defn send-mackie-feedback! [message enabled?]
  (when-let [feedback (daw/mackie-feedback-message message enabled?)]
    (when-let [access @midi-access-runtime]
      (doseq [output (array-seq (js/Array.from (.values (or (.-outputs access) (js/Map.)))))]
        (.send output (clj->js feedback))))))
(defn send-midi-bytes! [message]
  (when-let [access @midi-access-runtime]
    (doseq [output (array-seq (js/Array.from (.values (or (.-outputs access) (js/Map.)))))]
      (.send output (clj->js message)))))
(defn send-mackie-display! []
  (let [snapshot @state project (:project snapshot) profile-id (:mackie-active-profile snapshot)
        profile (daw/mackie-hardware-profile profile-id)]
    (when (and (:midi-sysex? snapshot) (:profile/lcd? profile))
      (send-midi-bytes! (daw/mackie-scribble-message (:project/tracks project) (:mackie-bank snapshot) profile-id)))
    (when (:profile/time-display? profile)
      (doseq [message (daw/mackie-time-display-messages project (:tick snapshot))]
        (send-midi-bytes! message)))))
(defn send-mackie-time! [tick]
  (let [slot (long (quot (max 0 tick) (max 1 (quot (get-in @state [:project :project/ppq]) 10))))]
    (when (and (:profile/time-display? (daw/mackie-hardware-profile (:mackie-active-profile @state)))
               (not= slot @mackie-time-slot))
      (reset! mackie-time-slot slot)
      (doseq [message (daw/mackie-time-display-messages (:project @state) tick)]
        (send-midi-bytes! message)))))
(defn handle-midi-message! [event]
  (let [data (.-data event) status (aget data 0) data1 (aget data 1) data2 (aget data 2)
        command (bit-and status 0xF0) channel (inc (bit-and status 0x0F))]
    (if-let [transport-command (daw/midi-transport-command status)]
      (do (case transport-command
            :start (start-playback! true)
            :continue (start-playback! false)
            :stop (stop-playback!))
          (swap! state assoc :midi-last-message (str "Transport " (name transport-command))))
      (if-let [mackie (daw/mackie-channel-message status data1 data2 (:mackie-bank @state))]
        (case (:mackie/action mackie)
          :bank
          (let [bank (daw/mackie-bank-offset (:mackie-bank @state) (:mackie/delta mackie)
                                             (count (get-in @state [:project :project/tracks])))]
            (swap! state assoc :mackie-bank bank :mackie-touched-strips #{}
                   :midi-last-message (str "Mackie bank " (inc (quot bank 8))))
            (send-mackie-display!))

          :fader-touch
          (let [strip (:mackie/strip mackie) touched? (:mackie/touched? mackie)
                track (get-in @state [:project :project/tracks strip])]
            (when (:profile/fader-touch? (daw/mackie-hardware-profile (:mackie-active-profile @state)))
              (swap! state update :mackie-touched-strips (if touched? (fnil conj #{}) disj) strip)
              (swap! state assoc :midi-last-message
                     (str "Mackie strip " (inc strip) (if touched? " touch" " release")))
              (when (and track (not touched?))
                (send-mackie-feedback! (assoc mackie :mackie/action :fader
                                              :mackie/value (or (:track/gain track) 1.0)) true))))

          (let [project (daw/apply-mackie-channel (:project @state) mackie)
                track (get (:project/tracks project) (:mackie/strip mackie))]
            (when track
              (let [enabled? (case (:mackie/action mackie)
                               :mute (:track/mute? track) :solo (:track/solo? track)
                               :record-arm (:track/armed? track) true)
                    feedback (if (= :pan (:mackie/action mackie))
                               (assoc mackie :mackie/value (:track/pan track)) mackie)]
                (swap! state assoc :project project
                       :midi-last-message (str "Mackie strip " (inc (:mackie/strip mackie)) " "
                                               (name (:mackie/action mackie))))
                (when (daw/mackie-motor-feedback? (:mackie-touched-strips @state) feedback)
                  (send-mackie-feedback! feedback enabled?))))))
        (when (= command 0xB0)
          (when-let [plugin-id (:midi-learning-plugin @state)]
            (swap! state update :project daw/set-midi-cc-mapping (str "midi:" plugin-id) channel data1 plugin-id)
            (swap! state assoc :midi-learning-plugin nil
                   :midi-last-message (str "Learned Ch " channel " CC " data1 " → " plugin-id)))
          (when-let [mapping (daw/midi-mapping-for (:project @state) channel data1)]
            (let [plugin-id (:target/plugin-id mapping)
                  mode (some (fn [plugin] (when (= plugin-id (:plugin/id plugin))
                                            (:plugin/automation-mode plugin)))
                             (get-in @state [:project :project/plugins]))]
              (when (= mode :latch) (swap! state update :mix-latched (fnil conj #{}) plugin-id))
              (swap! state update :project daw/apply-midi-cc channel data1 data2 (:tick @state))
              (send-midi-feedback! plugin-id (/ data2 127.0))
              (swap! state assoc :midi-last-message (str "Ch " channel " CC " data1 " = " data2)))))))))
(defn bind-midi-inputs! [access]
  (doseq [input (array-seq (js/Array.from (.values (.-inputs access))))]
    (set! (.-onmidimessage input) handle-midi-message!))
  (swap! state assoc :midi-input-count (.-size (.-inputs access))
         :midi-output-count (.-size (or (.-outputs access) (js/Map.)))))
(defn connect-midi! []
  (if-let [request (.-requestMIDIAccess js/navigator)]
    (let [connected! (fn [access]
                       (let [port-names (concat (map #(.-name %) (array-seq (js/Array.from (.values (.-inputs access)))))
                                                (map #(.-name %) (array-seq (js/Array.from (.values (or (.-outputs access) (js/Map.)))))))
                             selected (:mackie-profile @state)
                             active (if (= :auto selected) (daw/detect-mackie-hardware-profile port-names) selected)]
                       (reset! midi-access-runtime access)
                       (bind-midi-inputs! access)
                       (set! (.-onstatechange access) #(bind-midi-inputs! access))
                       (swap! state assoc :midi-status :connected :midi-sysex? (true? (.-sysexEnabled access)) :mackie-active-profile active
                              :project-error nil)
                       (reset! mackie-time-slot nil)
                       (send-mackie-display!))) ]
      (-> (.call request js/navigator #js {:sysex true})
        (.catch #(.call request js/navigator #js {:sysex false}))
        (.then connected!)
        (.catch #(swap! state assoc :midi-status :error
                        :project-error (str "MIDI failed: " (.-message %))))))
    (swap! state assoc :midi-status :unsupported :project-error "Web MIDI is not available")))
(defn disconnect-midi! []
  (when-let [access @midi-access-runtime]
    (doseq [input (array-seq (js/Array.from (.values (.-inputs access))))]
      (set! (.-onmidimessage input) nil))
    (set! (.-onstatechange access) nil))
  (reset! midi-access-runtime nil))
(defn stop-transport! []
  (when @transport-timer (js/clearInterval @transport-timer))
  (reset! transport-timer nil) (reset! transport-origin nil)
  (swap! state assoc :mix-latched #{}))
(defn transport-write! [tick]
  (doseq [plugin (get-in @state [:project :project/plugins])
          :let [mode (or (:plugin/automation-mode plugin) :read)
                latched? (contains? (:mix-latched @state) (:plugin/id plugin))]
          :when (or (= mode :write) (and (= mode :latch) latched?))]
    (swap! state update :project daw/write-plugin-mix-automation (:plugin/id plugin) tick
           (or (:plugin/mix plugin) 1.0) false latched?)))
(defn start-transport! []
  (stop-transport!)
  (let [project (:project @state) start-tick (:tick @state) started (.now js/performance)]
    (reset! transport-origin {:started started :start-tick start-tick})
    (reset! transport-timer
            (js/setInterval
             #(let [elapsed (/ (- (.now js/performance) started) 1000)
                    tick (+ start-tick (daw/seconds->ticks project elapsed))
                    end (daw/duration-ticks project)]
                (if (>= tick end)
                  (do (audio/stop!) (stop-meter!) (stop-transport!)
                      (swap! state assoc :playing? false :tick end))
                  (do (swap! state assoc :tick tick) (send-mackie-time! tick) (transport-write! tick)))) 25))))
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
(defn scan-audio-directory! [event]
  (let [files (->> (array-seq (.. event -target -files))
                   (filter #(.startsWith (or (.-type %) "") "audio/")) vec)]
    (swap! state assoc :directory-searching? true :directory-result nil :project-error nil)
    (-> (.all js/Promise
              (clj->js
               (map-indexed (fn [index file]
                              (-> (sha256-file! file)
                                  (.then (fn [sha256]
                                           {:file/index index :file/path (or (.-webkitRelativePath file) (.-name file))
                                            :file/name (.-name file) :file/sha256 sha256 :file/ref file})))) files)))
        (.then
         (fn [values]
           (let [plan (daw/directory-relink-plan (:project @state) (array-seq values))]
             (-> (.all js/Promise
                       (clj->js
                        (map (fn [{:asset/keys [id] :keys [candidate]}]
                               (let [file (:file/ref candidate)]
                                 (js/Promise.
                                  (fn [resolve reject]
                                    (audio/decode-file! file
                                                        #(resolve {:asset-id id :candidate candidate :file file :buffer %})
                                                        reject)))))
                             (:relink/matches plan))))
                 (.then (fn [decoded]
                          (doseq [{:keys [asset-id candidate file buffer]} (array-seq decoded)]
                            (swap! state (fn [s]
                                           (-> s (assoc-in [:buffers asset-id] buffer)
                                               (assoc-in [:assets asset-id]
                                                         {:name (.-name file) :relative-path (:file/path candidate)
                                                          :type (.-type file) :sha256 (:file/sha256 candidate) :blob file
                                                          :waveform (audio/waveform buffer 48)})))))
                          (swap! state assoc :directory-searching? false
                                 :directory-result {:matched (count decoded) :missing (:relink/missing plan)
                                                    :ignored (count (:relink/ignored-paths plan))})))))))
        (.catch #(swap! state assoc :directory-searching? false :project-error (str "Directory search failed: " (.-message %)))))))
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
(defn stop-playback! []
  (audio/stop!) (stop-meter!) (stop-transport!) (swap! state assoc :playing? false)
  (send-midi-transport-feedback! :stop))
(defn start-playback! [reset-to-zero?]
  (when reset-to-zero? (swap! state assoc :tick 0))
  (when-not (:playing? @state)
    (-> (audio/play! (:project @state) (:buffers @state)
                     (assoc (select-keys @state [:cutoff :delay]) :locate-tick (:tick @state)))
        (.then #(do (start-meter!) (swap! state assoc :playing? true :project-error nil)
                    (start-transport!) (send-midi-transport-feedback! (if reset-to-zero? :start :continue))))
        (.catch #(swap! state assoc :playing? false :project-error (str "Playback failed: " (.-message %)))))))
(defn toggle-play! []
  (if (:playing? @state) (stop-playback!) (start-playback! false)))
(defn export! []
  (swap! state assoc :exporting? true)
  (-> (audio/export-wav! (:project @state) (:buffers @state)
                         (select-keys @state [:cutoff :delay :normalize-export? :target-lufs :true-peak-ceiling-db])
                         #(swap! state assoc :exporting? false :loudness-report %))
      (.catch #(swap! state assoc :exporting? false :project-error (str "Master export failed: " (.-message %))))))
(defn analyze-master! []
  (swap! state assoc :analyzing? true :project-error nil)
  (-> (audio/analyze-project! (:project @state) (:buffers @state) (select-keys @state [:cutoff :delay])
                              #(swap! state assoc :analyzing? false :loudness-report %))
      (.catch #(swap! state assoc :analyzing? false :project-error (str "Master analysis failed: " (.-message %))))))
(defn export-stem! [track-id]
  (swap! state assoc :stem-exporting track-id)
  (audio/export-track-wav! (:project @state) track-id (:buffers @state) (select-keys @state [:cutoff :delay])
                           #(swap! state assoc :stem-exporting nil)))
(declare download-file!)
(defn export-stem-bundle! []
  (let [project (:project @state) effects (select-keys @state [:cutoff :delay])]
    (swap! state assoc :stem-bundle-exporting? true :project-error nil)
    (-> (audio/render-stems! project (:buffers @state) effects)
        (.then (fn [rendered]
                 (-> (.all js/Promise (clj->js (map #(.arrayBuffer (:blob %)) rendered)))
                     (.then (fn [values]
                              (let [entries #js {}]
                                (aset entries "project.edn" (strToU8 (pr-str project)))
                                (aset entries "stems.edn" (strToU8 (pr-str (daw/stem-bundle-manifest project))))
                                (doseq [[index array-buffer] (map-indexed vector (array-seq values))]
                                  (aset entries (daw/stem-entry-name index) (js/Uint8Array. array-buffer)))
                                (download-file! (js/Blob. #js [(zipSync entries #js {:level 0})]
                                                              #js {:type "application/zip"})
                                                "kami-daw-stems.kami.zip")))))))
        (.then #(swap! state assoc :stem-bundle-exporting? false))
        (.catch #(swap! state assoc :stem-bundle-exporting? false
                        :project-error (str "Stem bundle export failed: " (.-message %)))))))
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
                      :mackie-bank 0 :mackie-touched-strips #{} :buffers buffers :assets assets))))
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
                              :mackie-bank 0 :mackie-touched-strips #{} :buffers {} :assets {}))
                     (swap! state assoc :project-error "Unsupported or invalid DAW project"))
                   (catch :default error (swap! state assoc :project-error (.-message error)))))))))
(defn restore-recovery! []
  (when-let [text (.getItem js/localStorage recovery-key)]
    (try
      (if-let [{:keys [project history]} (daw/recover-workspace (reader/read-string text))]
        (let [first-clip (first (mapcat :track/clips (:project/tracks project)))]
          (swap! state assoc :project project :history history :selected (:clip/id first-clip)
                 :tick (or (:clip/start-tick first-clip) 0) :mackie-bank 0 :mackie-touched-strips #{}
                 :recovered? true :project-error nil))
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
    (.addEventListener js/window "beforeunload" (fn [_] (stop-input-monitor!) (disconnect-midi!)))
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
(defn set-bus-automation! [bus endpoint gain]
  (let [end-tick (daw/duration-ticks (:project @state))
        current (or (:bus/gain-automation bus)
                    [{:automation/tick 0 :automation/gain (or (:bus/gain bus) 1)}
                     {:automation/tick end-tick :automation/gain (or (:bus/gain bus) 1)}])
        points (mapv (fn [point] (if (= endpoint (:automation/tick point))
                                   {:tick endpoint :gain gain}
                                   {:tick (:automation/tick point) :gain (:automation/gain point)})) current)]
    (swap! state update :project daw/set-bus-gain-automation (:bus/id bus) points)))
(defn set-send-automation! [track endpoint send]
  (let [end-tick (daw/duration-ticks (:project @state))
        current (or (:track/send-automation track)
                    [{:automation/tick 0 :automation/send (or (:track/send track) 0)}
                     {:automation/tick end-tick :automation/send (or (:track/send track) 0)}])
        points (mapv (fn [point] (if (= endpoint (:automation/tick point))
                                   {:tick endpoint :send send}
                                   {:tick (:automation/tick point) :send (:automation/send point)})) current)]
    (swap! state update :project daw/set-send-automation (:track/id track) points)))
(defn set-effect-automation! [parameter endpoint value]
  (let [project (:project @state) end-tick (daw/duration-ticks project)
        base (get-in project [:project/master-effects parameter])
        current (or (seq (get-in project [:project/master-effects :effect/automation parameter]))
                    [{:automation/tick 0 :automation/value base}
                     {:automation/tick end-tick :automation/value base}])
        points (mapv (fn [point]
                       {:tick (:automation/tick point)
                        :value (if (= endpoint (:automation/tick point)) value (:automation/value point))}) current)]
    (swap! state update :project daw/set-effect-automation parameter points)))
(declare move-clip-drag! finish-clip-drag! cancel-clip-drag!)
(defn remove-clip-drag-listeners! []
  (.removeEventListener js/window "pointermove" move-clip-drag!)
  (.removeEventListener js/window "pointerup" finish-clip-drag!)
  (.removeEventListener js/window "pointercancel" cancel-clip-drag!))
(defn start-clip-drag! [event clip total]
  (when (= 0 (.-button event))
    (.preventDefault event)
    (let [lane (.closest (.-currentTarget event) ".lane") rect (.getBoundingClientRect lane)]
      (.addEventListener js/window "pointermove" move-clip-drag!)
      (.addEventListener js/window "pointerup" finish-clip-drag!)
      (.addEventListener js/window "pointercancel" cancel-clip-drag!)
      (swap! state assoc :selected (:clip/id clip) :tick (:clip/start-tick clip) :clip-preview (:project @state)
             :clip-drag {:pointer-id (.-pointerId event) :clip-id (:clip/id clip) :origin-x (.-clientX event)
                         :lane-width (.-width rect) :total total :origin-tick (:clip/start-tick clip)
                         :base-project (:project @state)}))))
(defn move-clip-drag! [event]
  (when-let [{:keys [pointer-id clip-id origin-x lane-width total origin-tick base-project]} (:clip-drag @state)]
    (when (= pointer-id (.-pointerId event))
      (.preventDefault event)
      (let [delta (js/Math.round (* (/ (- (.-clientX event) origin-x) lane-width) total))
            tick (max 0 (+ origin-tick delta))]
        (swap! state assoc :clip-preview (daw/move-clip base-project clip-id tick) :tick tick)))))
(defn finish-clip-drag! [event]
  (when (= (.-pointerId event) (get-in @state [:clip-drag :pointer-id]))
    (.preventDefault event)
    (let [preview (:clip-preview @state)]
      (remove-clip-drag-listeners!)
      (swap! state assoc :project preview :clip-preview nil :clip-drag nil))))
(defn cancel-clip-drag! [event]
  (when (= (.-pointerId event) (get-in @state [:clip-drag :pointer-id]))
    (remove-clip-drag-listeners!)
    (swap! state assoc :clip-preview nil :clip-drag nil :tick (get-in @state [:clip-drag :origin-tick]))))
(defn key-move-clip! [event clip]
  (when-let [direction ({"ArrowLeft" -1 "ArrowRight" 1} (.-key event))]
    (.preventDefault event) (.stopPropagation event)
    (let [step (if (.-shiftKey event) 480 120)
          tick (max 0 (+ (:clip/start-tick clip) (* direction step)))]
      (swap! state assoc :selected (:clip/id clip) :tick tick)
      (swap! state update :project daw/move-clip (:clip/id clip) tick))))
(defn track-row [track total]
  (let [asset-id (get-in track [:track/clips 0 :clip/asset-id]) asset (get-in @state [:assets asset-id])]
  [:div.daw-track [:div.daw-track-head [:strong (:track/name track)]
    [:div.daw-row (dds/button (if (:track/armed? track) "R ✓" "R") {:type :outline :size "sm" :attrs {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/armed? (not (:track/armed? track)))}})
     (dds/button (if (:track/mute? track) "M ✓" "M") {:type :outline :size "sm" :attrs {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/mute? (not (:track/mute? track)))}})
     (dds/button (if (:track/solo? track) "S ✓" "S") {:type :outline :size "sm" :attrs {:on-click #(swap! state update :project daw/set-track (:track/id track) :track/solo? (not (:track/solo? track)))}})]
    [:input {:type "file" :accept "audio/*" :aria-label (str "Import " (:track/name track) " audio") :on-change #(import-track! track %)}]
    (when asset [:small (:name asset)])
    (dds/button (if (= (:track/id track) (:recording @state)) "■ Stop take" "● Record") {:type :outline :size "sm" :attrs {:aria-label (str (if (= (:track/id track) (:recording @state)) "Stop " "Record ") (:track/name track))
              :disabled (and (:recording @state) (not= (:track/id track) (:recording @state)))
              :on-click #(if (= (:track/id track) (:recording @state)) (stop-recording!) (start-recording! (:track/id track)))}})
    (for [group (:track/take-lanes track)]
      ^{:key (:comp/id group)}
      [:div.comp-lane [:small (str "Comp • " (count (:comp/takes group)) " takes")]
       (for [take (:comp/takes group)]
         ^{:key (:clip/id take)}
         (dds/button (str "T" (:clip/take-index take)) {:type :outline :size "sm" :attrs {:aria-label (str "Select " (:track/name track) " comp take " (:clip/take-index take))
                   :class (when (= (:clip/id take) (:comp/active-take-id group)) "selected")
                   :on-click #(swap! state update :project daw/select-comp-take (:track/id track) (:comp/id group) (:clip/id take))}}))])
    (let [end-tick (daw/duration-ticks (:project @state)) points (:track/gain-automation track)
          start-gain (or (:automation/gain (first points)) (:track/gain track) 1)
          end-gain (or (:automation/gain (last points)) (:track/gain track) 1)]
      [:div.daw-row
       [:label "A→" [:input {:type "number" :min 0 :max 2 :step 0.05 :value start-gain :aria-label (str (:track/name track) " automation start") :on-change #(set-automation! track 0 (js/parseFloat (.. % -target -value)))}]]
       [:label "→B" [:input {:type "number" :min 0 :max 2 :step 0.05 :value end-gain :aria-label (str (:track/name track) " automation end") :on-change #(set-automation! track end-tick (js/parseFloat (.. % -target -value)))}]]])
    [:label "Send" [:input {:type "range" :min 0 :max 1 :step 0.05 :value (or (:track/send track) 0)
                             :aria-label (str (:track/name track) " delay send")
                             :on-change #(swap! state update :project daw/route-track (:track/id track) "master" (js/parseFloat (.. % -target -value)))}]]
    (let [end-tick (daw/duration-ticks (:project @state)) points (:track/send-automation track)
          start-send (or (:automation/send (first points)) (:track/send track) 0)
          end-send (or (:automation/send (last points)) (:track/send track) 0)]
      [:div.daw-row
       [:label "Send A→" [:input {:type "number" :min 0 :max 1 :step 0.05 :value start-send
                                    :aria-label (str (:track/name track) " send automation start")
                                    :on-change #(set-send-automation! track 0 (js/parseFloat (.. % -target -value)))}]]
       [:label "→B" [:input {:type "number" :min 0 :max 1 :step 0.05 :value end-send
                               :aria-label (str (:track/name track) " send automation end")
                               :on-change #(set-send-automation! track end-tick (js/parseFloat (.. % -target -value)))}]]])
    (dds/button (if (= (:track/id track) (:stem-exporting @state)) "Rendering stem…" "Export stem") {:type :outline :size "sm" :attrs {:on-click #(export-stem! (:track/id track)) :disabled (= (:track/id track) (:stem-exporting @state))}})
    [:input {:type "range" :min 0 :max 1 :step 0.01 :value (:track/gain track)
             :aria-label (str (:track/name track) " gain")
             :on-change #(swap! state update :project daw/set-track (:track/id track) :track/gain (js/parseFloat (.. % -target -value)))}]
    [:input {:type "range" :min -1 :max 1 :step 0.01 :value (or (:track/pan track) 0)
             :aria-label (str (:track/name track) " pan")
             :on-change #(swap! state update :project daw/set-track-pan (:track/id track)
                                (js/parseFloat (.. % -target -value)))}]]
   [:div.daw-lane (when asset [:div.waveform {:style {:position "absolute" :inset "8px" :display "flex" :align-items "center" :gap "2px" :opacity 0.45}}
                              (for [[i peak] (map-indexed vector (:waveform asset))] ^{:key i} [:i {:style {:display "block" :flex 1 :min-height "2px" :height (str (* 90 peak) "%") :background "var(--hig-color-tint)"}}])])
    (for [clip (:track/clips track)] ^{:key (:clip/id clip)}
    [:button.daw-clip {:style {:left (str (* 100 (/ (:clip/start-tick clip) total)) "%")
                           :width (str (* 100 (/ (:clip/length-ticks clip) total)) "%")
                           :background (:track/color track)}
                   :aria-label (str "Move " (:clip/name clip)) :aria-keyshortcuts "ArrowLeft ArrowRight Shift+ArrowLeft Shift+ArrowRight"
                   :on-pointer-down #(start-clip-drag! % clip total)
                   :on-key-down #(key-move-clip! % clip)
                   :on-click #(swap! state assoc :selected (:clip/id clip))} (:clip/name clip)])]]))
(defn selected-clip [project id] (some #(when (= id (:clip/id %)) %) (mapcat :track/clips (:project/tracks project))))
(defn edit-selected! [k value]
  (let [id (:selected @state) clip (selected-clip (:project @state) id)
        edit {:source-offset-sec (or (:clip/source-offset-sec clip) 0)
              :fade-in-sec (or (:clip/fade-in-sec clip) 0.02)
              :fade-out-sec (or (:clip/fade-out-sec clip) 0.05)}]
    (swap! state update :project daw/edit-clip id (assoc edit k value))))
(defn app [] (let [{:keys [playing? tick]} @state project (or (:clip-preview @state) (:project @state)) total (max 3840 (daw/duration-ticks project))
                    missing (daw/missing-asset-ids project (keys (:buffers @state)))]
 [:main.daw-main [:header.daw-toolbar
   [:div [:small.daw-eyebrow "KOTOBA-LANG / MUSIC"] [:h1 "KAMI DAW"]]
   [:span.daw-spacer]
   (dds/button (if playing? "■ Stop" "▶ Play audio") {:type :solid-fill :size "sm" :attrs {:on-click toggle-play!}})
   [:span.daw-readout (str "Tick " tick)]
   [:span.daw-readout (str (.toFixed (daw/tick->seconds project tick) 2) " s")]
   [:meter {:min -60 :max 0 :value (max -60 (:meter-db @state)) :title (str (.toFixed (:meter-db @state) 1) " dBFS")}]]
  ;; The user-test loop is an aside about the session, not a transport
  ;; control. Inside the toolbar it wrapped to four rows and pushed the
  ;; timeline below the fold; it belongs next to the other meta rows.
  [bench-panel]
 [:section.daw-meta [:label "Project" [:input {:value (:project/name project) :on-change #(swap! state assoc-in [:project :project/name] (.. % -target -value))}]]
   (dds/button "Load network assets" {:type :outline :size "sm" :attrs {:aria-label "Load network asset sources" :on-click load-network-sources!}})
   [:output {:aria-label "Network asset source status"} (:network-source-status @state)]
   (for [source (:network-sources @state) item (take 4 (:source/items source)) track (take 1 (:project/tracks project))]
     ^{:key (str (:source/id source) (:asset/id item))}
     (dds/button (str "Import " (:asset/name item)) {:type :outline :size "sm" :attrs {:aria-label (str "Import remote audio " (:asset/name item)) :on-click #(import-remote-audio! track item)}}))
   [:label "Tempo" [:input {:type "number" :value (:project/bpm project) :on-change #(swap! state assoc-in [:project :project/bpm] (js/parseInt (.. % -target -value)))}]]
   [:label "Immersive layout"
    [:select {:value (name (get-in project [:project/immersive :immersive/layout] :stereo))
              :aria-label "Immersive output layout"
              :on-change #(swap! state update :project daw/set-immersive-layout (keyword (.. % -target -value)))}
     [:option {:value "stereo"} "Stereo"] [:option {:value "surround-5.1"} "5.1 bed"]
     [:option {:value "surround-7.1.4"} "7.1.4 bed + objects"]]]
   (dds/button "Export ADM metadata" {:type :outline :size "sm" :attrs {:aria-label "Export ADM metadata"
             :on-click #(download-file! (js/Blob. #js [(daw/adm-xml project)] #js {:type "application/xml"})
                                        "kami-adm.xml")}})
   [:output {:aria-label "Immersive production status"}
    (str (count (daw/immersive-layout-channels (get-in project [:project/immersive :immersive/layout] :stereo)))
         " bed channels • ADM metadata • certified Atmos renderer external")]
   (dds/button "Connect MIDI" {:type :outline :size "sm" :attrs {:on-click connect-midi! :aria-label "Connect MIDI controller"}})
   [:label "Surface profile"
    [:select {:value (name (:mackie-profile @state)) :aria-label "Mackie hardware profile"
              :on-change #(let [selected (keyword (.. % -target -value))]
                            (swap! state assoc :mackie-profile selected
                                   :mackie-active-profile (if (= :auto selected) :generic-mcu selected)
                                   :mackie-touched-strips #{})
                            (reset! mackie-time-slot nil)
                            (send-mackie-display!))}
     [:option {:value "auto"} "Auto detect"]
     (for [[profile-id profile] (sort-by (comp :profile/name val) daw/mackie-hardware-profiles)]
       ^{:key profile-id} [:option {:value (name profile-id)} (:profile/name profile)])]]
   [:span {:aria-label "Active Mackie hardware profile"}
    (:profile/name (daw/mackie-hardware-profile (:mackie-active-profile @state)))]
   [:output {:aria-label "MIDI connection status"}
    (case (:midi-status @state) :connected (str "Connected inputs: " (:midi-input-count @state 0)
                                               " • outputs: " (:midi-output-count @state 0))
          :error "MIDI error" :unsupported "Web MIDI unavailable" "MIDI disconnected")]
   (when-let [message (:midi-last-message @state)] [:output {:aria-label "Last MIDI message"} message])
   (for [[parameter label minimum maximum step]
         [[:filter/cutoff-hz "Filter cutoff" 40 20000 10]
          [:delay/time-sec "Delay time" 0 1 0.01]
          [:delay/feedback "Delay feedback" 0 0.9 0.01]]
         :let [base (get-in project [:project/master-effects parameter])
               end-tick (daw/duration-ticks project)
               points (get-in project [:project/master-effects :effect/automation parameter])
               start-value (or (:automation/value (first points)) base)
               end-value (or (:automation/value (last points)) base)]]
     ^{:key parameter}
     [:span.effect-automation
      [:label label [:input {:type "number" :min minimum :max maximum :step step :value base
                              :aria-label (str label " base")
                              :on-change #(swap! state update :project daw/set-master-effect parameter
                                                 (js/parseFloat (.. % -target -value)))}]]
      [:label "A→" [:input {:type "number" :min minimum :max maximum :step step :value start-value
                              :aria-label (str label " automation start")
                              :on-change #(set-effect-automation! parameter 0 (js/parseFloat (.. % -target -value)))}]]
      [:label "→B" [:input {:type "number" :min minimum :max maximum :step step :value end-value
                              :aria-label (str label " automation end")
                              :on-change #(set-effect-automation! parameter end-tick (js/parseFloat (.. % -target -value)))}]]])
   (dds/button "Add AudioWorklet saturator" {:type :outline :size "sm" :attrs {:on-click #(swap! state update :project daw/add-plugin "master-saturator" :kami/saturator)
             :disabled (some (fn [plugin] (= "master-saturator" (:plugin/id plugin))) (:project/plugins project))}})
   (dds/button "Add AudioWorklet compressor" {:type :outline :size "sm" :attrs {:on-click #(swap! state update :project daw/add-plugin "master-compressor" :kami/compressor)
             :disabled (some (fn [plugin] (= "master-compressor" (:plugin/id plugin))) (:project/plugins project))}})
   [:label "Third-party AudioWorklet package"
    [:input {:type "file" :accept ".json,application/json" :aria-label "Import third-party AudioWorklet package"
             :on-change #(import-plugin-package! (aget (.. % -target -files) 0))}]]
   [:label "Signed publisher revocation list"
    [:input {:type "file" :accept ".json,application/json" :aria-label "Import signed publisher revocation list"
             :on-change #(import-revocation-list! (aget (.. % -target -files) 0))}]]
   (when-let [status (:revocation-status @state)]
     [:small {:aria-label "Revocation list status"} status])
   (when-let [status (:plugin-package-status @state)]
     [:small {:aria-label "Plugin package status"} status])
   (when-let [package @pending-plugin-package]
     (dds/button (str "Trust " (:package/publisher-name package)) {:type :outline :size "sm" :attrs {:aria-label "Trust signed plugin publisher"
               :on-click trust-pending-plugin-publisher!}}))
   (for [[publisher-id publisher] (get-in project [:project/trusted-publishers])]
     ^{:key publisher-id}
     [:span
      [:input {:value (get @publisher-rotation-drafts publisher-id "")
               :placeholder "New SHA-256 fingerprint"
               :aria-label (str "New fingerprint for " publisher-id)
               :on-change #(swap! publisher-rotation-drafts assoc publisher-id (.. % -target -value))}]
      (dds/button (str "Rotate " (:publisher/name publisher)) {:type :outline :size "sm" :attrs {:aria-label (str "Rotate publisher " publisher-id)
                :on-click #(swap! state update :project daw/rotate-plugin-publisher-key publisher-id
                                  (:publisher/fingerprint publisher)
                                  (get @publisher-rotation-drafts publisher-id "")
                                  (long (/ (.now js/Date) 1000)))}})
      (dds/button (str "Revoke " (:publisher/name publisher)) {:type :outline :size "sm" :attrs {:aria-label (str "Revoke publisher " publisher-id)
                :on-click #(swap! state update :project daw/revoke-plugin-publisher publisher-id)}})])
   (for [[plugin-index plugin] (map-indexed vector (:project/plugins project))]
     ^{:key (:plugin/id plugin)}
     [:span.effect-automation
      [:strong (:plugin/id plugin)]
      (when-let [diagnostic (get @audio/plugin-diagnostics (:plugin/id plugin))]
        [:small {:aria-label (str (:plugin/id plugin) " runtime status")} (name diagnostic)])
      (dds/button (if (= (:plugin/id plugin) (:midi-learning-plugin @state)) "Move a MIDI control…" "MIDI Learn") {:type :outline :size "sm" :attrs {:aria-label (str "Learn MIDI for " (:plugin/id plugin))
                :on-click #(swap! state assoc :midi-learning-plugin (:plugin/id plugin))}})
      [:label "MIDI channel" [:input {:type "number" :min 1 :max 16
                                       :value (get-in @state [:midi-map-drafts (:plugin/id plugin) :channel] 1)
                                       :aria-label (str (:plugin/id plugin) " MIDI channel")
                                       :on-change #(swap! state assoc-in [:midi-map-drafts (:plugin/id plugin) :channel]
                                                          (js/parseInt (.. % -target -value)))}]]
      [:label "MIDI CC" [:input {:type "number" :min 0 :max 127
                                  :value (get-in @state [:midi-map-drafts (:plugin/id plugin) :cc] 74)
                                  :aria-label (str (:plugin/id plugin) " MIDI CC")
                                  :on-change #(swap! state assoc-in [:midi-map-drafts (:plugin/id plugin) :cc]
                                                     (js/parseInt (.. % -target -value)))}]]
      (dds/button "Map MIDI" {:type :outline :size "sm" :attrs {:aria-label (str "Map MIDI to " (:plugin/id plugin))
                :on-click #(swap! state update :project daw/set-midi-cc-mapping
                                  (str "midi:" (:plugin/id plugin))
                                  (get-in @state [:midi-map-drafts (:plugin/id plugin) :channel] 1)
                                  (get-in @state [:midi-map-drafts (:plugin/id plugin) :cc] 74)
                                  (:plugin/id plugin))}})
      [:label "Enabled" [:input {:type "checkbox" :checked (:plugin/enabled? plugin)
                                   :aria-label (str (:plugin/id plugin) " enabled")
                                   :on-change #(swap! state update :project daw/set-plugin-enabled (:plugin/id plugin)
                                                      (.. % -target -checked))}]]
      (let [mix-value (or (:plugin/mix plugin) 1) mix-points (:plugin/mix-automation plugin)
            mix-start (or (:automation/value (first mix-points)) mix-value)
            mix-end (or (:automation/value (last mix-points)) mix-value)
            end-tick (daw/duration-ticks project)]
       [:span.effect-automation
        [:label "Mix curve" [:select {:value (name (or (:plugin/mix-interpolation plugin) :linear))
                                       :aria-label (str (:plugin/id plugin) " wet mix interpolation")
                                       :on-change #(swap! state update :project daw/set-plugin-mix-interpolation
                                                          (:plugin/id plugin) (keyword (.. % -target -value)))}
                             [:option {:value "linear"} "Linear"] [:option {:value "step"} "Step"]]]
        [:label "Wet mix" [:input {:type "number" :min 0 :max 1 :step 0.05
                                   :value (or (:plugin/mix plugin) 1)
                                   :aria-label (str (:plugin/id plugin) " wet mix")
                                   :on-change #(let [value (js/parseFloat (.. % -target -value))]
                                                 (swap! state update :project daw/set-plugin-mix (:plugin/id plugin) value)
                                                 (send-midi-feedback! (:plugin/id plugin) value))}]]
        [:label "Automation" [:select {:value (name (or (:plugin/automation-mode plugin) :read))
                                        :aria-label (str (:plugin/id plugin) " automation mode")
                                        :on-change #(let [mode (keyword (.. % -target -value))]
                                                     (swap! state update :project daw/set-plugin-automation-mode
                                                            (:plugin/id plugin) mode)
                                                     (when (not= mode :latch)
                                                       (swap! state update :mix-latched disj (:plugin/id plugin))))}
                               (for [mode [:read :touch :latch :write :trim]]
                                 ^{:key mode} [:option {:value (name mode)} (name mode)])]]
        [:label "Gesture" [:input {:type "range" :min 0 :max 1 :step 0.01 :value mix-value
                                    :aria-label (str (:plugin/id plugin) " live wet mix gesture")
                                    :on-input #(let [value (js/parseFloat (.. % -target -value))]
                                                 (swap! state update :project daw/set-plugin-mix (:plugin/id plugin) value)
                                                 (when (:playing? @state)
                                                   (when (= :latch (or (:plugin/automation-mode plugin) :read))
                                                     (swap! state update :mix-latched (fnil conj #{}) (:plugin/id plugin)))
                                                   (swap! state update :project daw/write-plugin-mix-automation
                                                          (:plugin/id plugin) (:tick @state) value true
                                                          (contains? (:mix-latched @state) (:plugin/id plugin)))))}]]
        (when (= :trim (or (:plugin/automation-mode plugin) :read))
          [:span
           [:label "Trim delta" [:input {:type "number" :min -1 :max 1 :step 0.01
                                          :value (get-in @state [:mix-trim-deltas (:plugin/id plugin)] 0)
                                          :aria-label (str (:plugin/id plugin) " automation trim delta")
                                          :on-change #(swap! state assoc-in [:mix-trim-deltas (:plugin/id plugin)]
                                                             (js/parseFloat (.. % -target -value)))}]]
           (dds/button "Apply trim" {:type :outline :size "sm" :attrs {:aria-label (str "Apply trim to " (:plugin/id plugin))
                     :on-click #(swap! state update :project daw/trim-plugin-mix-automation
                                       (:plugin/id plugin)
                                       (get-in @state [:mix-trim-deltas (:plugin/id plugin)] 0))}})])
        [:label "Thin tolerance" [:input {:type "number" :min 0 :max 1 :step 0.001
                                           :value (get-in @state [:mix-thin-tolerances (:plugin/id plugin)] 0.01)
                                           :aria-label (str (:plugin/id plugin) " automation thin tolerance")
                                           :on-change #(swap! state assoc-in [:mix-thin-tolerances (:plugin/id plugin)]
                                                              (js/parseFloat (.. % -target -value)))}]]
        (dds/button "Thin" {:type :outline :size "sm" :attrs {:aria-label (str "Thin automation for " (:plugin/id plugin))
                  :on-click #(swap! state update :project daw/thin-plugin-mix-automation
                                    (:plugin/id plugin)
                                    (get-in @state [:mix-thin-tolerances (:plugin/id plugin)] 0.01))}})
        [:label "A→" [:input {:type "number" :min 0 :max 1 :step 0.05 :value mix-start
                               :aria-label (str (:plugin/id plugin) " wet mix automation start")
                               :on-change #(swap! state update :project daw/set-plugin-mix-automation (:plugin/id plugin)
                                                  [{:tick 0 :value (js/parseFloat (.. % -target -value))}
                                                   {:tick end-tick :value mix-end}])}]]
        [:label "→B" [:input {:type "number" :min 0 :max 1 :step 0.05 :value mix-end
                               :aria-label (str (:plugin/id plugin) " wet mix automation end")
                               :on-change #(swap! state update :project daw/set-plugin-mix-automation (:plugin/id plugin)
                                                  [{:tick 0 :value mix-start}
                                                   {:tick end-tick :value (js/parseFloat (.. % -target -value))}])}]]])
      (dds/button "↑" {:type :outline :size "sm" :attrs {:aria-label (str "Move " (:plugin/id plugin) " up") :disabled (zero? plugin-index)
                :on-click #(swap! state update :project daw/move-plugin (:plugin/id plugin) :up)}})
      (dds/button "↓" {:type :outline :size "sm" :attrs {:aria-label (str "Move " (:plugin/id plugin) " down")
                :disabled (= plugin-index (dec (count (:project/plugins project))))
                :on-click #(swap! state update :project daw/move-plugin (:plugin/id plugin) :down)}})
      (dds/button "Remove" {:type :outline :size "sm" :attrs {:aria-label (str "Remove " (:plugin/id plugin))
                :on-click #(swap! state update :project daw/remove-plugin (:plugin/id plugin))}})
      (for [[parameter descriptor] (:plugin/parameters (daw/plugin-descriptor plugin))
            :let [base (get-in plugin [:plugin/parameters parameter])
                  points (get-in plugin [:plugin/automation parameter])
                  end-tick (daw/duration-ticks project)
                  start-value (or (:automation/value (first points)) base)
                  end-value (or (:automation/value (last points)) base)]]
        ^{:key parameter}
        [:span.effect-automation
         [:label (:parameter/name descriptor)
          [:input {:type "number" :min (:parameter/min descriptor) :max (:parameter/max descriptor)
                  :step (:parameter/step descriptor) :value base
                  :aria-label (str (:plugin/id plugin) " " (name parameter))
                  :on-change #(swap! state update :project daw/set-plugin-parameter (:plugin/id plugin) parameter
                                     (js/parseFloat (.. % -target -value)))}]]
         [:label "A→" [:input {:type "number" :min (:parameter/min descriptor) :max (:parameter/max descriptor)
                                :step (:parameter/step descriptor) :value start-value
                                :aria-label (str (:plugin/id plugin) " " (name parameter) " automation start")
                                :on-change #(swap! state update :project daw/set-plugin-automation (:plugin/id plugin) parameter
                                                   [{:tick 0 :value (js/parseFloat (.. % -target -value))}
                                                    {:tick end-tick :value end-value}])}]]
         [:label "→B" [:input {:type "number" :min (:parameter/min descriptor) :max (:parameter/max descriptor)
                                :step (:parameter/step descriptor) :value end-value
                                :aria-label (str (:plugin/id plugin) " " (name parameter) " automation end")
                                :on-change #(swap! state update :project daw/set-plugin-automation (:plugin/id plugin) parameter
                                                   [{:tick 0 :value start-value}
                                                    {:tick end-tick :value (js/parseFloat (.. % -target -value))}])}]]])])
   (for [bus (:project/buses project)
         :let [end-tick (daw/duration-ticks project) points (:bus/gain-automation bus)
               start-gain (or (:automation/gain (first points)) (:bus/gain bus) 1)
               end-gain (or (:automation/gain (last points)) (:bus/gain bus) 1)]]
     ^{:key (:bus/id bus)}
     [:span.bus-automation [:strong (str (:bus/name bus) " bus")]
      [:label "A→" [:input {:type "number" :min 0 :max 2 :step 0.05 :value start-gain
                              :aria-label (str (:bus/name bus) " bus automation start")
                              :on-change #(set-bus-automation! bus 0 (js/parseFloat (.. % -target -value)))}]]
      [:label "→B" [:input {:type "number" :min 0 :max 2 :step 0.05 :value end-gain
                              :aria-label (str (:bus/name bus) " bus automation end")
                              :on-change #(set-bus-automation! bus end-tick (js/parseFloat (.. % -target -value)))}]]])
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
   [:label "Normalize master" [:input {:type "checkbox" :checked (:normalize-export? @state)
                                        :aria-label "Normalize master export"
                                        :on-change #(swap! state assoc :normalize-export? (.. % -target -checked))}]]
   [:label "Target LUFS" [:input {:type "number" :min -30 :max -5 :step 1 :value (:target-lufs @state)
                                   :aria-label "Target integrated LUFS"
                                   :on-change #(swap! state assoc :target-lufs (js/parseFloat (.. % -target -value)))}]]
   [:label "True-peak ceiling" [:input {:type "number" :min -6 :max 0 :step 0.1 :value (:true-peak-ceiling-db @state)
                                         :aria-label "True peak ceiling dBTP"
                                         :on-change #(swap! state assoc :true-peak-ceiling-db (js/parseFloat (.. % -target -value)))}]]
   (dds/button (if (:analyzing? @state) "Analyzing…" "Analyze loudness") {:type :outline :size "sm" :attrs {:on-click analyze-master! :disabled (:analyzing? @state)}})
   (dds/button (if (:exporting? @state) "Rendering…" "Export WAV") {:type :outline :size "sm" :attrs {:on-click export! :disabled (:exporting? @state)}})
   (dds/button (if (:stem-bundle-exporting? @state) "Rendering all stems…" "Export all stems") {:type :outline :size "sm" :attrs {:on-click export-stem-bundle! :disabled (:stem-bundle-exporting? @state)}})
   (dds/button "Save project EDN" {:type :outline :size "sm" :attrs {:on-click download-project!}})
   [:label "Open project EDN" [:input {:type "file" :accept ".edn,application/edn" :aria-label "Open DAW project EDN" :on-change load-project!}]]
   (dds/button "Package project + media" {:type :outline :size "sm" :attrs {:on-click export-package!}})
   [:label "Open media package" [:input {:type "file" :accept ".zip,.kami.zip,application/zip" :aria-label "Open DAW media package" :on-change open-package!}]]
   [:label "Relink audio" [:input {:type "file" :accept "audio/*" :multiple true :aria-label "Relink DAW audio files" :on-change relink-audio!}]]
   [:label "Search audio directory" [:input {:type "file" :accept "audio/*" :multiple true :webkitdirectory ""
                                              :aria-label "Search DAW audio directory" :on-change scan-audio-directory!}]]
   (dds/button "↶ Undo" {:type :outline :size "sm" :attrs {:on-click undo! :disabled (empty? (get-in @state [:history :history/past])) :aria-label "Undo project edit"}})
   (dds/button "↷ Redo" {:type :outline :size "sm" :attrs {:on-click redo! :disabled (empty? (get-in @state [:history :history/future])) :aria-label "Redo project edit"}})
   (dds/button "Copy EDN" {:type :outline :size "sm" :attrs {:on-click #(js/navigator.clipboard.writeText (pr-str project))}})]
  (when-let [error (:project-error @state)] [:section.daw-meta [:strong (str "Project error: " error)]])
  (when-let [report (:loudness-report @state)]
    [:section.daw-meta.master-analysis
     [:strong (str "Master: " (.toFixed (:loudness/lufs report) 1) " LUFS • "
                   (.toFixed (:true-peak/dbtp report) 1) " dBTP")]
     (when (contains? report :normalization/gain-db)
       [:span (str "Delivery: " (.toFixed (:delivery/lufs report) 1) " LUFS • "
                   (.toFixed (:delivery/true-peak-dbtp report) 1) " dBTP • gain "
                   (.toFixed (:normalization/gain-db report) 1) " dB")])])
  (when (:directory-searching? @state) [:section.daw-meta [:strong "Searching audio directory…"]])
  (when-let [{:keys [matched missing ignored]} (:directory-result @state)]
    [:section.daw-meta [:strong (str "Directory relink: " matched " matched • " (count missing) " missing • " ignored " ignored")]])
  (when (:recovered? @state) [:section.daw-meta [:strong "Recovered autosaved project"]])
  (when (seq missing) [:section.daw-meta.missing-media [:strong (str "Missing media: " (count missing))] [:span (pr-str missing)]])
  (when-let [error (:recording-error @state)] [:section.daw-meta [:strong (str "Recording error: " error)]])
  (when-let [{:keys [take total]} (:recording-loop @state)] [:section.daw-meta [:strong (str "Capturing loop take " take " / " total)]])
  (when (:input-monitor-active? @state) [:section.daw-meta [:strong "Input monitor active • headphones recommended"]])
  (when-let [clip (selected-clip project (:selected @state))]
    [:section.daw-meta.clip-editor [:strong (str "Edit • " (:clip/name clip))]
     [:label "Source offset" [:input {:type "number" :min 0 :step 0.05 :value (or (:clip/source-offset-sec clip) 0) :on-change #(edit-selected! :source-offset-sec (js/parseFloat (.. % -target -value)))}]]
     [:label "Fade in" [:input {:type "number" :min 0 :step 0.05 :value (or (:clip/fade-in-sec clip) 0.02) :on-change #(edit-selected! :fade-in-sec (js/parseFloat (.. % -target -value)))}]]
     [:label "Fade out" [:input {:type "number" :min 0 :step 0.05 :value (or (:clip/fade-out-sec clip) 0.05) :on-change #(edit-selected! :fade-out-sec (js/parseFloat (.. % -target -value)))}]]])
  [:section.daw-editor [:div.daw-ruler [:span "1"] [:span "2"] [:span "3"] [:span "4"]]
   (for [track (:project/tracks project)] ^{:key (:track/id track)} [track-row track total])
   [:input.daw-scrub {:type "range" :min 0 :max total :value tick :on-change #(swap! state assoc :tick (js/parseInt (.. % -target -value)))}]]
  [:footer.daw-footer (if-let [errors (seq (daw/validate-project project))] (str "Errors: " errors) "Web Audio playback • low-pass + delay effects • offline WAV master")]]))
(defonce root-node (atom nil))
(defn init! []
  (.insertAdjacentHTML (.-head js/document) "beforeend" kotoba-html-contract)
  (when-not @root-node
    (restore-recovery!) (install-history!) (install-autosave!) (install-shortcuts!)
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app]))
