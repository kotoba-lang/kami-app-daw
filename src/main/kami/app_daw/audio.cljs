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
        feedback (.createGain ctx)]
    (set! (.-type filter) "lowpass")
    (set! (.. filter -frequency -value) cutoff)
    (set! (.. delay -delayTime -value) delay-seconds)
    (set! (.. feedback -gain -value) (if (pos? delay-seconds) 0.28 0))
    (.connect filter delay) (.connect delay feedback) (.connect feedback delay)
    (.connect filter destination) (.connect delay destination)
    {:input filter}))

(defn- schedule! [ctx destination project]
  (let [start (+ (.-currentTime ctx) 0.05)]
    (doseq [track (:project/tracks project)
            :when (not (:track/mute? track))
            clip (:track/clips track)]
      (let [osc (.createOscillator ctx) gain (.createGain ctx)
            begin (+ start (daw/tick->seconds project (:clip/start-tick clip)))
            duration (daw/tick->seconds project (:clip/length-ticks clip))
            level (* 0.16 (or (:track/gain track) 1))]
        (set! (.-type osc) (if (= "drums" (:track/id track)) "square" "sine"))
        (set! (.. osc -frequency -value) (get frequencies (:track/id track) 220))
        (.setValueAtTime (.-gain gain) 0 begin)
        (.linearRampToValueAtTime (.-gain gain) level (+ begin 0.02))
        (.setValueAtTime (.-gain gain) level (+ begin (max 0.03 (- duration 0.05))))
        (.linearRampToValueAtTime (.-gain gain) 0 (+ begin duration))
        (.connect osc gain) (.connect gain destination)
        (.start osc begin) (.stop osc (+ begin duration))))
    start))

(defn play! [project {:keys [cutoff delay]}]
  (stop!)
  (let [ctx (audio-context)
        chain (connect-chain! ctx (.-destination ctx) cutoff delay)
        start (schedule! ctx (:input chain) project)]
    (.resume ctx)
    (reset! runtime {:context ctx :started start})
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

(defn export-wav! [project {:keys [cutoff delay]} on-done]
  (let [seconds (+ 1 (daw/tick->seconds project (daw/duration-ticks project)))
        ctx (js/OfflineAudioContext. 1 (js/Math.ceil (* 44100 seconds)) 44100)
        chain (connect-chain! ctx (.-destination ctx) cutoff delay)]
    (schedule! ctx (:input chain) project)
    (-> (.startRendering ctx)
        (.then (fn [buffer]
                 (let [url (js/URL.createObjectURL (wav-blob buffer)) a (.createElement js/document "a")]
                   (set! (.-href a) url) (set! (.-download a) "kami-daw-master.wav") (.click a)
                   (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
                   (on-done)))))))
