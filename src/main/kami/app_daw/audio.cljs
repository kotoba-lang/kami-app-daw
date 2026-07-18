(ns kami.app-daw.audio
  (:require [kami.app-daw.core :as daw]))

(defonce runtime (atom nil))
(def frequencies {"drums" 110 "synth" 261.63 "voice" 329.63})

(defn- audio-context []
  (let [Ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
    (new Ctor)))

(defn stop! []
  (when-let [ctx (:context @runtime)] (.close ctx))
  (reset! runtime nil))

(defn- connect-chain! [ctx destination cutoff delay-seconds]
  (let [filter (.createBiquadFilter ctx)
        delay (.createDelay ctx 1.0)
        feedback (.createGain ctx)
        analyser (.createAnalyser ctx)]
    (set! (.-type filter) "lowpass")
    (set! (.. filter -frequency -value) cutoff)
    (set! (.. delay -delayTime -value) delay-seconds)
    (set! (.. feedback -gain -value) (if (pos? delay-seconds) 0.28 0))
    (.connect filter delay) (.connect delay feedback) (.connect feedback delay)
    (set! (.-fftSize analyser) 512)
    (.connect filter analyser) (.connect delay analyser) (.connect analyser destination)
    {:input filter :analyser analyser}))

(defn meter-db []
  (if-let [analyser (:analyser @runtime)]
    (let [samples (js/Float32Array. (.-fftSize analyser))]
      (.getFloatTimeDomainData analyser samples)
      (let [sum (reduce (fn [acc x] (+ acc (* x x))) 0 (array-seq samples)) rms (js/Math.sqrt (/ sum (.-length samples)))]
        (if (pos? rms) (* 20 (/ (js/Math.log rms) (js/Math.log 10))) -96)))
    -96))

(defn decode-file! [file on-ready]
  (let [ctx (audio-context)]
    (-> (.arrayBuffer file) (.then #(.decodeAudioData ctx %))
        (.then (fn [buffer] (on-ready buffer) (.close ctx))))))

(defn waveform [buffer points]
  (let [samples (.getChannelData buffer 0) stride (max 1 (quot (.-length samples) points))]
    (mapv (fn [point]
            (loop [i (* point stride) peak 0]
              (if (< i (min (.-length samples) (* (inc point) stride)))
                (recur (inc i) (max peak (js/Math.abs (aget samples i)))) peak)))
          (range points))))

(defn- schedule! [ctx destination project buffers]
  (let [start (+ (.-currentTime ctx) 0.05)]
    (doseq [track (:project/tracks project)
            :when (not (:track/mute? track))
            clip (:track/clips track)]
      (let [buffer (get buffers (:clip/asset-id clip))
            source (if buffer (.createBufferSource ctx) (.createOscillator ctx)) gain (.createGain ctx)
            begin (+ start (daw/tick->seconds project (:clip/start-tick clip)))
            duration (daw/tick->seconds project (:clip/length-ticks clip))
            offset (or (:clip/source-offset-sec clip) 0)
            actual-duration (if buffer (min duration (max 0 (- (.-duration buffer) offset))) duration)
            fade-in (min actual-duration (or (:clip/fade-in-sec clip) 0.02))
            fade-out (min (- actual-duration fade-in) (or (:clip/fade-out-sec clip) 0.05))
            level (* 0.16 (or (:track/gain track) 1))]
        (if buffer (set! (.-buffer source) buffer)
            (do (set! (.-type source) (if (= "drums" (:track/id track)) "square" "sine"))
                (set! (.. source -frequency -value) (get frequencies (:track/id track) 220))))
        (.setValueAtTime (.-gain gain) 0 begin)
        (.linearRampToValueAtTime (.-gain gain) level (+ begin fade-in))
        (.setValueAtTime (.-gain gain) level (+ begin (max fade-in (- actual-duration fade-out))))
        (.linearRampToValueAtTime (.-gain gain) 0 (+ begin actual-duration))
        (.connect source gain) (.connect gain destination)
        (if buffer (.start source begin offset actual-duration) (.start source begin))
        (.stop source (+ begin actual-duration))))
    start))

(defn play! [project buffers {:keys [cutoff delay]}]
  (stop!)
  (let [ctx (audio-context)
        chain (connect-chain! ctx (.-destination ctx) cutoff delay)
        start (schedule! ctx (:input chain) project buffers)]
    (.resume ctx)
    (reset! runtime {:context ctx :started start :analyser (:analyser chain)})
    ctx))

(defn- write-string! [view offset value]
  (doseq [i (range (count value))] (.setUint8 view (+ offset i) (.charCodeAt value i))))

(defn- wav-blob [buffer]
  (let [samples (.getChannelData buffer 0) size (+ 44 (* 2 (.-length samples)))
        ab (js/ArrayBuffer. size) view (js/DataView. ab) rate (.-sampleRate buffer)]
    (write-string! view 0 "RIFF") (.setUint32 view 4 (- size 8) true)
    (write-string! view 8 "WAVEfmt ") (.setUint32 view 16 16 true)
    (.setUint16 view 20 1 true) (.setUint16 view 22 1 true) (.setUint32 view 24 rate true)
    (.setUint32 view 28 (* rate 2) true) (.setUint16 view 32 2 true) (.setUint16 view 34 16 true)
    (write-string! view 36 "data") (.setUint32 view 40 (* 2 (.-length samples)) true)
    (doseq [i (range (.-length samples))]
      (let [x (max -1 (min 1 (aget samples i)))]
        (.setInt16 view (+ 44 (* i 2)) (* x (if (neg? x) 32768 32767)) true)))
    (js/Blob. #js [ab] #js {:type "audio/wav"})))

(defn export-wav! [project buffers {:keys [cutoff delay]} on-done]
  (let [seconds (+ 1 (daw/tick->seconds project (daw/duration-ticks project)))
        ctx (js/OfflineAudioContext. 1 (js/Math.ceil (* 44100 seconds)) 44100)
        chain (connect-chain! ctx (.-destination ctx) cutoff delay)]
    (schedule! ctx (:input chain) project buffers)
    (-> (.startRendering ctx)
        (.then (fn [buffer]
                 (let [url (js/URL.createObjectURL (wav-blob buffer)) a (.createElement js/document "a")]
                   (set! (.-href a) url) (set! (.-download a) "kami-daw-master.wav") (.click a)
                   (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
                   (on-done)))))))
