(ns kami.app-daw.audio
  (:require [kami.app-daw.core :as daw]))

(defonce runtime (atom nil))
(defonce monitor-runtime (atom nil))
(defonce worklet-module-url (atom nil))
(def oscillator-frequencies {"drums" 110 "synth" 261.63 "voice" 329.63})

(def saturator-worklet-source
  "class KamiSaturator extends AudioWorkletProcessor {\n  static get parameterDescriptors() { return [{name: 'drive', defaultValue: 1, minValue: 0.1, maxValue: 8, automationRate: 'k-rate'}]; }\n  process(inputs, outputs, parameters) {\n    const input = inputs[0]; const output = outputs[0]; const drive = parameters.drive[0]; const norm = Math.tanh(drive);\n    for (let channel = 0; channel < output.length; channel++) { const source = input[channel] || input[0]; if (!source) continue; for (let i = 0; i < output[channel].length; i++) output[channel][i] = Math.tanh(source[i] * drive) / norm; }\n    return true;\n  }\n}\nregisterProcessor('kami-saturator', KamiSaturator);")

(def compressor-worklet-source
  "class KamiCompressor extends AudioWorkletProcessor {\n  static get parameterDescriptors() { return [{name: 'threshold-db', defaultValue: -18, minValue: -60, maxValue: 0, automationRate: 'k-rate'}, {name: 'ratio', defaultValue: 4, minValue: 1, maxValue: 20, automationRate: 'k-rate'}, {name: 'makeup-db', defaultValue: 0, minValue: 0, maxValue: 24, automationRate: 'k-rate'}]; }\n  process(inputs, outputs, parameters) {\n    const input = inputs[0], output = outputs[0], threshold = parameters['threshold-db'][0], ratio = parameters.ratio[0], makeup = Math.pow(10, parameters['makeup-db'][0] / 20);\n    for (let channel = 0; channel < output.length; channel++) { const source = input[channel] || input[0]; if (!source) continue; for (let i = 0; i < output[channel].length; i++) { const sample = source[i], magnitude = Math.abs(sample); if (magnitude === 0) { output[channel][i] = 0; continue; } const db = 20 * Math.log10(magnitude), compressedDb = db > threshold ? threshold + (db - threshold) / ratio : db; output[channel][i] = Math.sign(sample) * Math.pow(10, compressedDb / 20) * makeup; } }\n    return true;\n  }\n}\nregisterProcessor('kami-compressor', KamiCompressor);")

(defn- worklet-url []
  (or @worklet-module-url
      (let [url (js/URL.createObjectURL (js/Blob. #js [saturator-worklet-source compressor-worklet-source]
                                                     #js {:type "text/javascript"}))]
        (reset! worklet-module-url url) url)))

(defn- ensure-worklets! [ctx project]
  (if (seq (filter :plugin/enabled? (:project/plugins project)))
    (if-let [audio-worklet (.-audioWorklet ctx)]
      (.addModule audio-worklet (worklet-url))
      (js/Promise.reject (js/Error. "AudioWorklet is unavailable in this rendering context")))
    (js/Promise.resolve true)))

(defn- audio-context []
  (let [Ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
    (new Ctor)))

(defn stop! []
  (when-let [ctx (:context @runtime)] (.close ctx))
  (reset! runtime nil))

(defn stop-input-monitor! []
  (when-let [{:keys [source gain analyser context]} @monitor-runtime]
    (doseq [node [source gain analyser]] (when node (.disconnect node)))
    (when context (.close context)))
  (reset! monitor-runtime nil))

(defn start-input-monitor! [stream level]
  (stop-input-monitor!)
  (let [ctx (audio-context) source (.createMediaStreamSource ctx stream)
        gain (.createGain ctx) analyser (.createAnalyser ctx)]
    (set! (.. gain -gain -value) (daw/monitor-gain level))
    (set! (.-fftSize analyser) 512)
    (.connect source gain) (.connect gain analyser) (.connect analyser (.-destination ctx))
    (.resume ctx)
    (reset! monitor-runtime {:context ctx :source source :gain gain :analyser analyser})
    ctx))

(defn set-input-monitor-gain! [level]
  (when-let [gain (:gain @monitor-runtime)]
    (set! (.. gain -gain -value) (daw/monitor-gain level))))

(defn input-monitor-db []
  (if-let [analyser (:analyser @monitor-runtime)]
    (let [samples (js/Float32Array. (.-fftSize analyser))]
      (.getFloatTimeDomainData analyser samples)
      (let [sum (reduce (fn [acc x] (+ acc (* x x))) 0 (array-seq samples))
            rms (js/Math.sqrt (/ sum (.-length samples)))]
        (if (pos? rms) (* 20 (/ (js/Math.log rms) (js/Math.log 10))) -96)))
    -96))

(defn- schedule-param! [param project start base points tick-key value-key interpolation locate-tick]
  (.setValueAtTime param (daw/automation-value-at points locate-tick tick-key value-key base interpolation) start)
  (doseq [point (filter #(> (tick-key %) locate-tick) points)]
    (let [time (+ start (daw/tick->seconds project (- (tick-key point) locate-tick)))]
      (if (= :step interpolation)
        (.setValueAtTime param (value-key point) time)
        (.linearRampToValueAtTime param (value-key point) time)))))
(defn- schedule-effect-param! [param project start base points locate-tick]
  (schedule-param! param project start base points :automation/tick :automation/value :linear locate-tick))

(defn- connect-chain! [ctx destination project effects locate-tick]
  (let [authority (:project/master-effects project)
        cutoff (or (:filter/cutoff-hz authority) (:cutoff effects) 4200)
        delay-seconds (or (:delay/time-sec authority) (:delay effects) 0.12)
        feedback-value (or (:delay/feedback authority) 0.28)
        automation (:effect/automation authority)
        start (+ (.-currentTime ctx) 0.05)
        filter (.createBiquadFilter ctx)
        delay (.createDelay ctx 1.0)
        feedback (.createGain ctx)
        mix (.createGain ctx)
        analyser (.createAnalyser ctx)]
    (set! (.-type filter) "lowpass")
    (schedule-effect-param! (.-frequency filter) project start cutoff (get automation :filter/cutoff-hz) locate-tick)
    (schedule-effect-param! (.-delayTime delay) project start delay-seconds (get automation :delay/time-sec) locate-tick)
    (schedule-effect-param! (.-gain feedback) project start feedback-value (get automation :delay/feedback) locate-tick)
    (.connect filter delay) (.connect delay feedback) (.connect feedback delay)
    (set! (.-fftSize analyser) 512)
    (.connect filter mix) (.connect delay mix)
    (let [output (reduce
                  (fn [input plugin]
                    (if (:plugin/enabled? plugin)
                      (let [node (js/AudioWorkletNode. ctx (:plugin/processor plugin)
                                                       #js {:numberOfInputs 1 :numberOfOutputs 1
                                                            :outputChannelCount #js [2]})
                            mix-value (or (:plugin/mix plugin) 1.0)
                            mix-points (:plugin/mix-automation plugin)
                            dry (.createGain ctx) wet (.createGain ctx) sum (.createGain ctx)
                            parameters (get-in daw/plugin-types [(:plugin/kind plugin) :plugin/parameters])]
                        (let [interpolation (or (:plugin/mix-interpolation plugin) :linear)]
                          (schedule-param! (.-gain wet) project start mix-value mix-points :automation/tick :automation/value interpolation locate-tick)
                          (schedule-param! (.-gain dry) project start (- 1 mix-value)
                                           (mapv #(update % :automation/value (fn [value] (- 1 value))) mix-points)
                                           :automation/tick :automation/value interpolation locate-tick))
                        (doseq [[parameter descriptor] parameters
                                :let [audio-param (.get (.-parameters node) (name parameter))]]
                          (when audio-param
                            (schedule-effect-param!
                             audio-param project start
                             (get-in plugin [:plugin/parameters parameter] (:parameter/default descriptor))
                             (get-in plugin [:plugin/automation parameter]) locate-tick)))
                        (.connect input dry) (.connect dry sum)
                        (.connect input node) (.connect node wet) (.connect wet sum)
                        sum)
                      input)) mix (:project/plugins project))]
      (.connect output analyser))
    (.connect analyser destination)
    {:input filter :analyser analyser :filter filter :delay delay :feedback feedback :mix mix}))

(defn meter-db []
  (if-let [analyser (:analyser @runtime)]
    (let [samples (js/Float32Array. (.-fftSize analyser))]
      (.getFloatTimeDomainData analyser samples)
      (let [sum (reduce (fn [acc x] (+ acc (* x x))) 0 (array-seq samples)) rms (js/Math.sqrt (/ sum (.-length samples)))]
        (if (pos? rms) (* 20 (/ (js/Math.log rms) (js/Math.log 10))) -96)))
    -96))

(defn decode-file!
  ([file on-ready] (decode-file! file on-ready #(js/console.error "Audio decode failed" %)))
  ([file on-ready on-error]
   (let [ctx (audio-context)]
     (-> (.arrayBuffer file) (.then #(.decodeAudioData ctx %))
         (.then (fn [buffer] (on-ready buffer) (.close ctx)))
         (.catch (fn [error] (.close ctx) (on-error error)))))))

(defn waveform [buffer points]
  (let [samples (.getChannelData buffer 0) stride (max 1 (quot (.-length samples) points))]
    (mapv (fn [point]
            (loop [i (* point stride) peak 0]
              (if (< i (min (.-length samples) (* (inc point) stride)))
                (recur (inc i) (max peak (js/Math.abs (aget samples i)))) peak)))
          (range points))))

(defn- schedule! [ctx destination project buffers locate-tick]
  (let [start (+ (.-currentTime ctx) 0.05)
        bus-nodes (into {} (map (fn [bus] (let [gain (.createGain ctx)]
                                            (set! (.. gain -gain -value) (or (:bus/gain bus) 1))
                                            (.connect gain destination) [(:bus/id bus) gain])) (:project/buses project)))
        default-bus (or (get bus-nodes "master") destination)
        send-delay (.createDelay ctx 1.0) send-feedback (.createGain ctx)
        soloed? (some :track/solo? (:project/tracks project))]
    (doseq [bus (:project/buses project)
            :let [node (get bus-nodes (:bus/id bus))]]
      (schedule-param! (.-gain node) project start (or (:bus/gain bus) 1)
                       (:bus/gain-automation bus) :automation/tick :automation/gain :linear locate-tick))
    (set! (.. send-delay -delayTime -value) 0.18) (set! (.. send-feedback -gain -value) 0.32)
    (.connect send-delay send-feedback) (.connect send-feedback send-delay) (.connect send-delay destination)
    (doseq [track (:project/tracks project)
            :when (and (not (:track/mute? track)) (or (not soloed?) (:track/solo? track)))
            clip (:track/clips track)
            :let [window (daw/clip-playback-window project clip locate-tick)]
            :when window]
      (let [buffer (get buffers (:clip/asset-id clip))
            source (if buffer (.createBufferSource ctx) (.createOscillator ctx)) gain (.createGain ctx) track-gain (.createGain ctx)
            panner (.createStereoPanner ctx)
            begin (+ start (:playback/delay-sec window))
            duration (:playback/duration-sec window)
            offset (:playback/source-offset-sec window)
            actual-duration (if buffer (min duration (max 0 (- (.-duration buffer) offset))) duration)
            fade-in (min actual-duration (or (:clip/fade-in-sec clip) 0.02))
            fade-out (min (- actual-duration fade-in) (or (:clip/fade-out-sec clip) 0.05))
            level 0.16]
        (if buffer (set! (.-buffer source) buffer)
            (do (set! (.-type source) (if (= "drums" (:track/id track)) "square" "sine"))
                (set! (.. source -frequency -value) (get oscillator-frequencies (:track/id track) 220))))
        (.setValueAtTime (.-gain gain) 0 begin)
        (.linearRampToValueAtTime (.-gain gain) level (+ begin fade-in))
        (.setValueAtTime (.-gain gain) level (+ begin (max fade-in (- actual-duration fade-out))))
        (.linearRampToValueAtTime (.-gain gain) 0 (+ begin actual-duration))
        (schedule-param! (.-gain track-gain) project start (or (:track/gain track) 1)
                         (:track/gain-automation track) :automation/tick :automation/gain :linear locate-tick)
        (set! (.. panner -pan -value) (or (:track/pan track) 0))
        (let [bus (or (get bus-nodes (:track/bus-id track)) default-bus)
              send-gain (.createGain ctx)]
          (schedule-param! (.-gain send-gain) project start (or (:track/send track) 0)
                           (:track/send-automation track) :automation/tick :automation/send :linear locate-tick)
          (.connect source gain) (.connect gain track-gain) (.connect track-gain panner) (.connect panner bus)
          (.connect panner send-gain) (.connect send-gain send-delay))
        (if buffer (.start source begin offset actual-duration) (.start source begin))
        (.stop source (+ begin actual-duration))))
    start))

(defn play! [project buffers {:keys [cutoff delay locate-tick]}]
  (stop!)
  (let [ctx (audio-context)]
    (-> (ensure-worklets! ctx project)
        (.then (fn []
                 (let [locate-tick (max 0 (or locate-tick 0))
                       chain (connect-chain! ctx (.-destination ctx) project {:cutoff cutoff :delay delay} locate-tick)
                       start (schedule! ctx (:input chain) project buffers locate-tick)]
                   (.resume ctx)
                   (reset! runtime {:context ctx :started start :analyser (:analyser chain)})
                   ctx)))
        (.catch (fn [error] (.close ctx) (throw error))))))

(defn- write-string! [view offset value]
  (doseq [i (range (count value))] (.setUint8 view (+ offset i) (.charCodeAt value i))))

(defn- wav-blob [buffer gain]
  (let [channels (.-numberOfChannels buffer) frames (.-length buffer)
        channel-data (mapv #(.getChannelData buffer %) (range channels))
        data-size (* 2 channels frames) size (+ 44 data-size)
        ab (js/ArrayBuffer. size) view (js/DataView. ab) rate (.-sampleRate buffer)]
    (write-string! view 0 "RIFF") (.setUint32 view 4 (- size 8) true)
    (write-string! view 8 "WAVEfmt ") (.setUint32 view 16 16 true)
    (.setUint16 view 20 1 true) (.setUint16 view 22 channels true) (.setUint32 view 24 rate true)
    (.setUint32 view 28 (* rate channels 2) true) (.setUint16 view 32 (* channels 2) true) (.setUint16 view 34 16 true)
    (write-string! view 36 "data") (.setUint32 view 40 data-size true)
    (doseq [frame (range frames) channel (range channels)]
      (let [x (max -1 (min 1 (* gain (aget (nth channel-data channel) frame))))
            index (+ (* frame channels) channel)]
        (.setInt16 view (+ 44 (* index 2)) (* x (if (neg? x) 32768 32767)) true)))
    (js/Blob. #js [ab] #js {:type "audio/wav"})))

(defn- render-project! [project buffers {:keys [cutoff delay]}]
  (let [seconds (+ 1 (daw/tick->seconds project (daw/duration-ticks project)))
        ctx (js/OfflineAudioContext. 2 (js/Math.ceil (* 44100 seconds)) 44100)]
    (-> (ensure-worklets! ctx project)
        (.then (fn []
                 (let [chain (connect-chain! ctx (.-destination ctx) project {:cutoff cutoff :delay delay} 0)]
                   (schedule! ctx (:input chain) project buffers 0)
                   (.startRendering ctx)))))))

(defn- k-weight! [buffer]
  (let [ctx (js/OfflineAudioContext. (.-numberOfChannels buffer) (.-length buffer) (.-sampleRate buffer))
        source (.createBufferSource ctx) high-pass (.createBiquadFilter ctx) shelf (.createBiquadFilter ctx)]
    (set! (.-buffer source) buffer)
    (set! (.-type high-pass) "highpass") (set! (.. high-pass -frequency -value) 38) (set! (.. high-pass -Q -value) 0.5)
    (set! (.-type shelf) "highshelf") (set! (.. shelf -frequency -value) 1682) (set! (.. shelf -gain -value) 4)
    (.connect source high-pass) (.connect high-pass shelf) (.connect shelf (.-destination ctx))
    (.start source 0)
    (.startRendering ctx)))

(defn- oversample-4x! [buffer]
  (let [rate (* 4 (.-sampleRate buffer)) ctx (js/OfflineAudioContext. (.-numberOfChannels buffer) (* 4 (.-length buffer)) rate)
        source (.createBufferSource ctx)]
    (set! (.-buffer source) buffer) (.connect source (.-destination ctx)) (.start source 0)
    (.startRendering ctx)))

(defn- block-powers [buffer]
  (let [channels (mapv #(.getChannelData buffer %) (range (.-numberOfChannels buffer))) rate (.-sampleRate buffer)
        window (max 1 (js/Math.round (* 0.4 rate))) step (max 1 (js/Math.round (* 0.1 rate)))
        limit (.-length buffer)]
    (loop [start 0 powers []]
      (if (>= start limit) powers
          (let [end (min limit (+ start window))
                sum (reduce + (map (fn [samples]
                                     (loop [i start acc 0]
                                       (if (< i end) (let [x (aget samples i)] (recur (inc i) (+ acc (* x x)))) acc)))
                                   channels))]
            (recur (+ start step) (conj powers (/ sum (max 1 (- end start))))))))))

(defn- peak-db [buffer]
  (let [peak (reduce max 0
                     (map (fn [channel]
                            (let [samples (.getChannelData buffer channel)]
                              (loop [i 0 value 0]
                                (if (< i (.-length samples))
                                  (recur (inc i) (max value (js/Math.abs (aget samples i)))) value))))
                          (range (.-numberOfChannels buffer))))]
    (if (pos? peak) (* 20 (js/Math.log10 peak)) -96.0)))

(defn analyze-buffer! [buffer]
  (-> (.all js/Promise #js [(k-weight! buffer) (oversample-4x! buffer)])
      (.then (fn [values]
               {:loudness/lufs (daw/integrated-lufs (block-powers (aget values 0)))
                :true-peak/dbtp (peak-db (aget values 1))}))))

(defn analyze-project! [project buffers effects on-done]
  (-> (render-project! project buffers effects)
      (.then analyze-buffer!)
      (.then on-done)))

(defn export-wav! [project buffers effects on-done]
  (-> (render-project! project buffers effects)
      (.then (fn [buffer]
               (-> (analyze-buffer! buffer)
                   (.then (fn [report]
                            (let [gain-db (if (:normalize-export? effects)
                                            (daw/normalization-gain-db (:loudness/lufs report) (:true-peak/dbtp report)
                                                                       (:target-lufs effects) (:true-peak-ceiling-db effects)) 0)
                                  gain (js/Math.pow 10 (/ gain-db 20))
                                  result (assoc report :normalization/gain-db gain-db
                                                       :delivery/lufs (+ (:loudness/lufs report) gain-db)
                                                       :delivery/true-peak-dbtp (+ (:true-peak/dbtp report) gain-db))
                                  url (js/URL.createObjectURL (wav-blob buffer gain)) a (.createElement js/document "a")]
                              (set! (.-href a) url) (set! (.-download a) "kami-daw-master.wav") (.click a)
                              (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
                              (on-done result)))))))))

(defn render-track-wav! [project track-id buffers effects]
  (let [stem (daw/solo-track-project project track-id)]
    (let [seconds (+ 1 (daw/tick->seconds project (daw/duration-ticks project)))
          ctx (js/OfflineAudioContext. 2 (js/Math.ceil (* 44100 seconds)) 44100)]
      (-> (ensure-worklets! ctx stem)
          (.then (fn []
                   (let [chain (connect-chain! ctx (.-destination ctx) stem effects 0)]
                     (schedule! ctx (:input chain) stem buffers 0)
                     (.startRendering ctx))))
          (.then #(wav-blob % 1))))))

(defn render-stems! [project buffers effects]
  (reduce (fn [promise track]
            (.then promise
                   (fn [rendered]
                     (-> (render-track-wav! project (:track/id track) buffers effects)
                         (.then #(conj rendered {:track/id (:track/id track) :blob %}))))))
          (js/Promise.resolve []) (:project/tracks project)))

(defn export-track-wav! [project track-id buffers effects on-done]
  (-> (render-track-wav! project track-id buffers effects)
      (.then (fn [blob]
               (let [url (js/URL.createObjectURL blob) a (.createElement js/document "a")]
                 (set! (.-href a) url) (set! (.-download a) (str "kami-daw-stem-" track-id ".wav")) (.click a)
                 (js/setTimeout #(js/URL.revokeObjectURL url) 1000) (on-done))))))
