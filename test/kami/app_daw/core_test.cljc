(ns kami.app-daw.core-test (:require [clojure.test :refer [deftest is]] [kami.app-daw.core :as daw]))
(def p (daw/project {:project/tracks [{:track/id "t" :track/clips [{:clip/id "c" :clip/start-tick 0 :clip/length-ticks 960}]}]}))
(deftest transport-and-edit (is (= 960 (daw/duration-ticks p))) (is (= 1.0 (daw/tick->seconds p 960)))
  (is (= 240 (get-in (daw/move-clip p "c" 240) [:project/tracks 0 :track/clips 0 :clip/start-tick])))
  (is (zero? (get-in (daw/move-clip p "c" -240) [:project/tracks 0 :track/clips 0 :clip/start-tick])))
  (is (empty? (daw/validate-project p))))
(deftest sample-accurate-clip-playback-window
  (let [clip {:clip/start-tick 480 :clip/length-ticks 1920 :clip/source-offset-sec 0.25}]
    (is (= {:playback/delay-sec 0.5 :playback/source-offset-sec 0.25 :playback/duration-sec 2.0}
           (daw/clip-playback-window p clip 0)))
    (is (= {:playback/delay-sec 0.0 :playback/source-offset-sec 1.25 :playback/duration-sec 1.0}
           (daw/clip-playback-window p clip 1440)))
    (is (nil? (daw/clip-playback-window p clip 2400)))))
(deftest locate-aware-automation-value
  (let [points [{:tick 0 :value 0.0} {:tick 960 :value 1.0} {:tick 1920 :value 0.5}]]
    (is (= 0.25 (daw/automation-value-at points 240 :tick :value 0.8 :linear)))
    (is (= 0.0 (daw/automation-value-at points 240 :tick :value 0.8 :step)))
    (is (= 0.8 (daw/automation-value-at [] 240 :tick :value 0.8 :linear)))
    (is (= 0.5 (daw/automation-value-at [{:tick 480 :value 0.0}] 240 :tick :value 1.0 :linear)))
    (is (= 0.5 (daw/automation-value-at points 2400 :tick :value 0.8 :linear)))))
(deftest non-destructive-clip-edit
  (let [edited (daw/edit-clip p "c" {:source-offset-sec 0.25 :fade-in-sec 0.1 :fade-out-sec 0.2})
        clip (get-in edited [:project/tracks 0 :track/clips 0])]
    (is (= [0.25 0.1 0.2] ((juxt :clip/source-offset-sec :clip/fade-in-sec :clip/fade-out-sec) clip)))
    (is (empty? (daw/validate-project edited)))))
(deftest automation-and-stem
  (let [automated (daw/set-gain-automation p "t" [{:tick 960 :gain 0.2} {:tick 0 :gain 1.0}])]
    (is (= [0 960] (mapv :automation/tick (get-in automated [:project/tracks 0 :track/gain-automation]))))
    (is (= ["t"] (mapv :track/id (:project/tracks (daw/solo-track-project automated "t")))))
    (is (empty? (daw/validate-project automated)))))
(deftest deterministic-stem-bundle-manifest
  (let [manifest (daw/stem-bundle-manifest p)]
    (is (= [1 daw/schema 44100 16 960]
           ((juxt :stem-bundle/version :stem-bundle/project-schema :stem-bundle/sample-rate
                  :stem-bundle/bit-depth :stem-bundle/timeline-end-tick) manifest)))
    (is (= [{:stem/track-id "t" :stem/track-name nil :stem/channel-layout :stereo
             :stem/entry-name "stems/0.wav"}]
           (:stem-bundle/tracks manifest)))
    (is (= manifest (daw/accept-stem-bundle-manifest p manifest #{"stems/0.wav"})))
    (is (nil? (daw/accept-stem-bundle-manifest p manifest #{})))))
(deftest project-authoritative-track-pan
  (let [left (daw/set-track-pan p "t" -2) right (daw/set-track-pan p "t" 2)]
    (is (= -1.0 (get-in left [:project/tracks 0 :track/pan])))
    (is (= 1.0 (get-in right [:project/tracks 0 :track/pan])))
    (is (empty? (daw/validate-project left)))
    (is (= [[:invalid-track-pan "t"]]
           (daw/validate-project (assoc-in p [:project/tracks 0 :track/pan] 1.1))))))
(deftest project-authoritative-audio-worklet-plugin
  (let [plugged (-> p
                    (daw/add-plugin "sat" :kami/saturator)
                    (daw/set-plugin-parameter "sat" :drive 20)
                    (daw/set-plugin-enabled "sat" false))
        plugin (first (:project/plugins plugged))]
    (is (= ["sat" :kami/saturator :audio-worklet "kami-saturator" false 8.0]
           [(:plugin/id plugin) (:plugin/kind plugin) (:plugin/type plugin) (:plugin/processor plugin)
            (:plugin/enabled? plugin) (get-in plugin [:plugin/parameters :drive])]))
    (is (empty? (daw/validate-project plugged)))
    (is (= [:duplicate-plugin-id]
           (daw/validate-project (update plugged :project/plugins conj plugin))))
    (is (= [[:invalid-plugin "sat"]]
           (daw/validate-project (assoc-in plugged [:project/plugins 0 :plugin/processor] "unknown"))))))
(deftest manifest-driven-compressor-parameters
  (let [plugged (daw/add-plugin p "comp" :kami/compressor)
        edited (-> plugged
                   (daw/set-plugin-parameter "comp" :threshold-db -24)
                   (daw/set-plugin-parameter "comp" :ratio 30)
                   (daw/set-plugin-parameter "comp" :makeup-db 6))]
    (is (= {:threshold-db -18.0 :ratio 4.0 :makeup-db 0.0}
           (:plugin/parameters (first (:project/plugins plugged)))))
    (is (= {:threshold-db -24 :ratio 20.0 :makeup-db 6}
           (:plugin/parameters (first (:project/plugins edited)))))
    (is (empty? (daw/validate-project edited)))
    (is (= [[:invalid-plugin "comp"]]
           (daw/validate-project (assoc-in edited [:project/plugins 0 :plugin/parameters :unknown] 1))))))
(deftest plugin-parameter-automation-is-sorted-and-bounded
  (let [automated (-> p (daw/add-plugin "comp" :kami/compressor)
                      (daw/set-plugin-automation "comp" :threshold-db
                                                 [{:tick 960 :value 8} {:tick -4 :value -80}]))]
    (is (= [{:automation/tick 0 :automation/value -60.0}
            {:automation/tick 960 :automation/value 0.0}]
           (get-in automated [:project/plugins 0 :plugin/automation :threshold-db])))
    (is (empty? (daw/validate-project automated)))
    (is (= [[:invalid-plugin "comp"]]
           (daw/validate-project
            (assoc-in automated [:project/plugins 0 :plugin/automation :threshold-db 0 :automation/value] -90))))))
(deftest plugin-chain-order-and-removal-are-project-authority
  (let [chain (-> p (daw/add-plugin "sat" :kami/saturator) (daw/add-plugin "comp" :kami/compressor))
        moved (daw/move-plugin chain "comp" :up)
        removed (daw/remove-plugin moved "sat")]
    (is (= ["sat" "comp"] (mapv :plugin/id (:project/plugins chain))))
    (is (= ["comp" "sat"] (mapv :plugin/id (:project/plugins moved))))
    (is (= moved (daw/move-plugin moved "comp" :up)))
    (is (= ["comp"] (mapv :plugin/id (:project/plugins removed))))
    (is (empty? (daw/validate-project removed)))))
(deftest plugin-wet-dry-mix-is-bounded-project-data
  (let [plugin (daw/add-plugin p "sat" :kami/saturator)
        half (daw/set-plugin-mix plugin "sat" 0.35)]
    (is (= 1.0 (get-in plugin [:project/plugins 0 :plugin/mix])))
    (is (= 0.35 (get-in half [:project/plugins 0 :plugin/mix])))
    (is (= 0.0 (get-in (daw/set-plugin-mix half "sat" -2) [:project/plugins 0 :plugin/mix])))
    (is (= 1.0 (get-in (daw/set-plugin-mix half "sat" 3) [:project/plugins 0 :plugin/mix])))
    (is (empty? (daw/validate-project half)))
    (is (= [[:invalid-plugin "sat"]]
           (daw/validate-project (assoc-in half [:project/plugins 0 :plugin/mix] 1.2))))))
(deftest plugin-mix-automation-is-complementary-bounded-data
  (let [automated (-> p (daw/add-plugin "sat" :kami/saturator)
                      (daw/set-plugin-mix-automation "sat"
                                                     [{:tick 1920 :value 2} {:tick -5 :value -1}]))]
    (is (= [{:automation/tick 0 :automation/value 0.0}
            {:automation/tick 1920 :automation/value 1.0}]
           (get-in automated [:project/plugins 0 :plugin/mix-automation])))
    (is (empty? (daw/validate-project automated)))
    (is (= [[:invalid-plugin "sat"]]
           (daw/validate-project
            (assoc-in automated [:project/plugins 0 :plugin/mix-automation 0 :automation/value] -0.1))))))
(deftest third-party-audio-worklet-packages-are-bounded-project-authority
  (let [package {:package/version 1 :package/id "vendor.gain"
                 :package/source-sha256 "0000000000000000000000000000000000000000000000000000000000000000"
                 :package/capabilities #{:audio-processing}
                 :package/source "class GainProcessor {}\nregisterProcessor('vendor-gain', GainProcessor);"
                 :package/descriptor
                 {:plugin/name "Vendor Gain" :plugin/processor "vendor-gain"
                  :plugin/parameters {:gain {:parameter/name "Gain" :parameter/min 0.0
                                             :parameter/max 2.0 :parameter/default 1.0
                                             :parameter/step 0.01}}}}
        installed (daw/add-third-party-plugin p "vendor-gain-1" package)]
    (is (empty? (daw/third-party-plugin-package-errors package)))
    (is (= "vendor.gain" (get-in installed [:project/plugins 0 :plugin/package-id])))
    (is (= 1.0 (get-in installed [:project/plugins 0 :plugin/parameters :gain])))
    (is (= 2.0 (get-in (daw/set-plugin-parameter installed "vendor-gain-1" :gain 9)
                       [:project/plugins 0 :plugin/parameters :gain])))
    (is (empty? (daw/validate-project installed)))
    (is (= [[:invalid-plugin-source]]
           (daw/third-party-plugin-package-errors (assoc package :package/source ""))))
    (is (= [[:invalid-plugin-manifest]]
           (daw/third-party-plugin-package-errors (assoc package :package/version 2))))
    (is (= [[:invalid-plugin "vendor-gain-1"]]
           (daw/validate-project (assoc-in installed [:project/plugins 0 :plugin/source] ""))))))
(deftest plugin-mix-interpolation-is-validated-project-authority
  (let [plugin (daw/add-plugin p "sat" :kami/saturator)
        stepped (daw/set-plugin-mix-interpolation plugin "sat" :step)]
    (is (= :linear (get-in plugin [:project/plugins 0 :plugin/mix-interpolation])))
    (is (= :step (get-in stepped [:project/plugins 0 :plugin/mix-interpolation])))
    (is (= stepped (daw/set-plugin-mix-interpolation stepped "sat" :bezier)))
    (is (empty? (daw/validate-project stepped)))
    (is (= [[:invalid-plugin "sat"]]
           (daw/validate-project (assoc-in stepped [:project/plugins 0 :plugin/mix-interpolation] :curve))))))
(deftest live-plugin-mix-write-coalesces-musical-ticks
  (let [written (-> (daw/add-plugin p "sat" :kami/saturator)
                    (daw/append-plugin-mix-automation-point "sat" 960 0.25)
                    (daw/append-plugin-mix-automation-point "sat" 240 2)
                    (daw/append-plugin-mix-automation-point "sat" 960 0.8))]
    (is (= [{:automation/tick 240 :automation/value 1.0}
            {:automation/tick 960 :automation/value 0.8}]
           (get-in written [:project/plugins 0 :plugin/mix-automation])))
    (is (empty? (daw/validate-project written)))))
(deftest plugin-automation-modes-govern-transport-writes
  (let [base (daw/add-plugin p "sat" :kami/saturator)
        touch (daw/set-plugin-automation-mode base "sat" :touch)
        latch (daw/set-plugin-automation-mode base "sat" :latch)
        write (daw/set-plugin-automation-mode base "sat" :write)]
    (is (= base (daw/write-plugin-mix-automation base "sat" 120 0.2 true false)))
    (is (empty? (get-in (daw/write-plugin-mix-automation touch "sat" 120 0.2 false false)
                        [:project/plugins 0 :plugin/mix-automation])))
    (is (= [120] (mapv :automation/tick (get-in (daw/write-plugin-mix-automation touch "sat" 120 0.2 true false)
                                                 [:project/plugins 0 :plugin/mix-automation]))))
    (is (= [240] (mapv :automation/tick (get-in (daw/write-plugin-mix-automation latch "sat" 240 0.3 false true)
                                                 [:project/plugins 0 :plugin/mix-automation]))))
    (is (= [360] (mapv :automation/tick (get-in (daw/write-plugin-mix-automation write "sat" 360 0.4 false false)
                                                 [:project/plugins 0 :plugin/mix-automation]))))
    (is (= :trim (get-in (daw/set-plugin-automation-mode write "sat" :trim)
                         [:project/plugins 0 :plugin/automation-mode])))
    (is (empty? (daw/validate-project write)))))
(deftest mix-automation-thinning-and-trim-preserve-authoritative-shape
  (let [base (-> (daw/add-plugin p "sat" :kami/saturator)
                 (daw/set-plugin-mix-automation "sat" [{:tick 0 :value 0.1}
                                                        {:tick 240 :value 0.205}
                                                        {:tick 480 :value 0.3}
                                                        {:tick 960 :value 0.9}]))
        thinned (daw/thin-plugin-mix-automation base "sat" 0.01)
        trimmed (daw/trim-plugin-mix-automation thinned "sat" 0.2)]
    (is (= [0 480 960] (mapv :automation/tick (get-in thinned [:project/plugins 0 :plugin/mix-automation]))))
    (is (every? true? (map #(<= (Math/abs (- %1 %2)) 1.0e-9)
                           [0.3 0.5 1.0]
                           (map :automation/value (get-in trimmed [:project/plugins 0 :plugin/mix-automation])))))
    (is (= base (daw/thin-plugin-mix-automation base "sat" -0.1)))
    (is (empty? (daw/validate-project trimmed)))))
(deftest midi-cc-mapping-controls-and-writes-plugin-mix
  (let [base (-> (daw/add-plugin p "sat" :kami/saturator)
                 (daw/set-plugin-automation-mode "sat" :touch)
                 (daw/set-midi-cc-mapping "mix-knob" 1 74 "sat"))
        controlled (daw/apply-midi-cc base 1 74 64 720)]
    (is (= "sat" (:target/plugin-id (daw/midi-mapping-for base 1 74))))
    (is (= [176 74 64] (daw/midi-feedback-message (daw/midi-mapping-for base 1 74) (/ 64.0 127.0))))
    (is (= :start (daw/midi-transport-command 0xFA)))
    (is (= :continue (daw/midi-transport-command 0xFB)))
    (is (= :stop (daw/midi-transport-command 0xFC)))
    (is (= [0xFA] (daw/midi-transport-message :start)))
    (is (<= (Math/abs (- (/ 64.0 127.0) (get-in controlled [:project/plugins 0 :plugin/mix]))) 1.0e-9))
    (is (= [720] (mapv :automation/tick (get-in controlled [:project/plugins 0 :plugin/mix-automation]))))
    (is (= base (daw/apply-midi-cc base 2 74 127 720)))
    (is (empty? (:project/midi-mappings (daw/remove-plugin base "sat"))))
    (is (empty? (daw/validate-project controlled)))
    (is (= [:duplicate-midi-cc-mapping]
           (daw/validate-project (update base :project/midi-mappings conj
                                         {:midi/id "other" :midi/channel 1 :midi/cc 74
                                          :target/type :plugin/mix :target/plugin-id "sat"}))))))
(deftest mackie-control-channel-strips
  (let [project (daw/project {:project/tracks [{:track/id "a" :track/gain 0.1}
                                                {:track/id "b" :track/gain 0.2}]})
        fader (daw/mackie-channel-message 0xE1 0x7F 0x7F)
        mute (daw/mackie-channel-message 0x90 0x10 0x7F)
        solo (daw/mackie-channel-message 0x90 0x09 0x7F)]
    (is (= {:mackie/action :fader :mackie/strip 1 :mackie/local-strip 1 :mackie/value 1.0} fader))
    (is (= 1.0 (get-in (daw/apply-mackie-channel project fader) [:project/tracks 1 :track/gain])))
    (is (true? (get-in (daw/apply-mackie-channel project mute) [:project/tracks 0 :track/mute?])))
    (is (true? (get-in (daw/apply-mackie-channel project solo) [:project/tracks 1 :track/solo?])))
    (is (= [225 127 127] (daw/mackie-feedback-message fader true)))
    (is (= [144 16 127] (daw/mackie-feedback-message mute true)))
    (is (nil? (daw/mackie-channel-message 0x90 0x10 0)))))
(deftest mackie-bank-pan-and-record-arm
  (let [tracks (mapv (fn [index] {:track/id (str "t" index) :track/pan 0}) (range 10))
        project (daw/project {:project/tracks tracks})
        pan (daw/mackie-channel-message 0xB0 0x10 0x05 8)
        arm (daw/mackie-channel-message 0x90 0x00 0x7F 8)]
    (is (= 8 (daw/mackie-bank-offset 0 8 10)))
    (is (= 0 (daw/mackie-bank-offset 8 -8 10)))
    (is (= {:mackie/action :bank :mackie/delta 8} (daw/mackie-channel-message 0x90 0x2F 0x7F 0)))
    (is (= 0.1 (get-in (daw/apply-mackie-channel project pan) [:project/tracks 8 :track/pan])))
    (is (true? (get-in (daw/apply-mackie-channel project arm) [:project/tracks 8 :track/armed?])))
    (is (= [176 16 70] (daw/mackie-feedback-message (assoc pan :mackie/value 0.1) true)))
    (is (= [144 0 127] (daw/mackie-feedback-message arm true)))))
(deftest mackie-fader-touch-owns-motor-feedback
  (let [touch (daw/mackie-channel-message 0x90 0x68 0x7F 8)
        release (daw/mackie-channel-message 0x90 0x68 0x00 8)
        fader (daw/mackie-channel-message 0xE0 0x7F 0x7F 8)]
    (is (= {:mackie/action :fader-touch :mackie/strip 8 :mackie/local-strip 0
            :mackie/touched? true} touch))
    (is (false? (:mackie/touched? release)))
    (is (false? (daw/mackie-motor-feedback? #{8} fader)))
    (is (true? (daw/mackie-motor-feedback? #{} fader)))
    (is (true? (daw/mackie-motor-feedback? #{9} fader)))))
(deftest mackie-scribble-strip-and-time-display
  (let [message (daw/mackie-scribble-message [{:track/name "Drums"} {:track/name "日本語Voice"}] 0)
        banked (daw/mackie-scribble-message (mapv #(hash-map :track/name (str "Track" %)) (range 10)) 8)
        time (daw/mackie-time-display-messages p 2460)]
    (is (= 64 (count message)))
    (is (= [240 0 0 102 20 18 0] (subvec message 0 7)))
    (is (= (mapv int "Drums  ") (subvec message 7 14)))
    (is (= (mapv int "???Voic") (subvec message 14 21)))
    (is (= 247 (last message)))
    (is (= (mapv int "Track8 ") (subvec banked 7 14)))
    (is (= 10 (count time)))
    (is (= [176 64 48] (first time)))
    (is (= [176 73 48] (last time)))
    (is (= (mapv int "0600012200") (mapv last time)))))
(deftest mackie-hardware-profiles-detect-and-bound-capabilities
  (is (= :behringer-x-touch (daw/detect-mackie-hardware-profile ["X-TOUCH INT"])))
  (is (= :icon-platform-m-plus (daw/detect-mackie-hardware-profile ["Platform M+ V2"])))
  (is (= :mackie-control (daw/detect-mackie-hardware-profile ["Mackie Control USB"])))
  (is (= :generic-mcu (daw/detect-mackie-hardware-profile ["Unknown MIDI"])))
  (is (= 8 (:profile/bank-size (daw/mackie-hardware-profile :unknown))))
  (is (= [240 0 0 102 20 18 0]
         (subvec (daw/mackie-scribble-message [{:track/name "A"}] 0 :behringer-x-touch) 0 7))))
(deftest project-authoritative-bus-automation
  (let [automated (daw/set-bus-gain-automation p "master" [{:tick 960 :gain 0.25} {:tick 0 :gain 1.0}])]
    (is (= [{:automation/tick 0 :automation/gain 1.0} {:automation/tick 960 :automation/gain 0.25}]
           (get-in automated [:project/buses 0 :bus/gain-automation])))
    (is (empty? (daw/validate-project automated)))
    (is (= [[:invalid-bus-automation-order "master"]]
           (daw/validate-project (assoc-in automated [:project/buses 0 :bus/gain-automation]
                                           [{:automation/tick 960 :automation/gain 1} {:automation/tick 0 :automation/gain 1}]))))))
(deftest project-authoritative-send-automation
  (let [automated (daw/set-send-automation p "t" [{:tick 960 :send 2} {:tick 0 :send 0.1}])]
    (is (= [{:automation/tick 0 :automation/send 0.1} {:automation/tick 960 :automation/send 1}]
           (get-in automated [:project/tracks 0 :track/send-automation])))
    (is (empty? (daw/validate-project automated)))
    (is (= [[:invalid-send-automation-order "t"]]
           (daw/validate-project (assoc-in automated [:project/tracks 0 :track/send-automation]
                                           [{:automation/tick 960 :automation/send 1} {:automation/tick 0 :automation/send 0}]))))))
(deftest project-authoritative-effect-parameter-automation
  (let [automated (-> p
                      (daw/set-master-effect :delay/feedback 2)
                      (daw/set-effect-automation :filter/cutoff-hz
                                                 [{:tick 960 :value 25000} {:tick 0 :value 80}]))]
    (is (= 0.9 (get-in automated [:project/master-effects :delay/feedback])))
    (is (= [{:automation/tick 0 :automation/value 80}
            {:automation/tick 960 :automation/value 20000.0}]
           (get-in automated [:project/master-effects :effect/automation :filter/cutoff-hz])))
    (is (empty? (daw/validate-project automated)))
    (is (= [[:invalid-effect-automation :delay/time-sec]]
           (daw/validate-project
            (assoc-in automated [:project/master-effects :effect/automation :delay/time-sec]
                      [{:automation/tick 960 :automation/value 0.2}
                       {:automation/tick 0 :automation/value 0.1}]))))))
(deftest bus-routing
  (let [routed (daw/route-track p "t" "master" 0.35)]
    (is (= ["master" 0.35] ((juxt :track/bus-id :track/send) (get-in routed [:project/tracks 0]))))
    (is (empty? (daw/validate-project routed)))
    (is (= [[:missing-bus "t" "missing"]]
           (daw/validate-project (daw/route-track p "t" "missing" 0.1))))))
(deftest gated-loudness-and-true-peak-safe-normalization
  (is (< (abs (- -0.691 (daw/power->lufs 1.0))) 0.001))
  (is (< (abs (- -20.691 (daw/integrated-lufs [0.01 0.01 0.0]))) 0.001))
  (is (= -2.0 (daw/normalization-gain-db -20.0 -0.5 -14.0 -2.5)))
  (is (= 6.0 (daw/normalization-gain-db -20.0 -10.0 -14.0 -1.0)))
  (is (zero? (daw/normalization-gain-db -96.0 -96.0 -14.0 -1.0))))
(deftest recorded-take-becomes-authoritative-clip
  (let [recorded (daw/add-recorded-clip p "t" "recording:1" 480 1.25 "take-1")
        clip (last (get-in recorded [:project/tracks 0 :track/clips]))]
    (is (= ["take-1" "recording:1" 480 1200]
           ((juxt :clip/id :clip/asset-id :clip/start-tick :clip/length-ticks) clip)))
    (is (empty? (daw/validate-project recorded)))))
(deftest loop-takes-retain-lanes-and-project-active-comp
  (let [take-1 (daw/add-comp-take p "t" "comp:1" "recording:1" 480 1.0 "take-1" 1)
        take-2 (daw/add-comp-take take-1 "t" "comp:1" "recording:2" 480 1.0 "take-2" 2)
        selected (daw/select-comp-take take-2 "t" "comp:1" "take-1")
        track (get-in selected [:project/tracks 0]) group (first (:track/take-lanes track))]
    (is (= ["take-1" "take-2"] (mapv :clip/id (:comp/takes group))))
    (is (= "take-1" (:comp/active-take-id group)))
    (is (= "recording:1" (:clip/asset-id (last (:track/clips track)))))
    (is (empty? (daw/validate-project selected)))
    (is (= [[:invalid-comp-take "t" "comp:1" "take-2"]]
           (daw/validate-project (assoc-in selected [:project/tracks 0 :track/take-lanes 0 :comp/takes 1 :clip/length-ticks] 0))))
    (is (= [[:missing-active-comp-take "t" "comp:1"]]
           (daw/validate-project (assoc-in selected [:project/tracks 0 :track/take-lanes 0 :comp/active-take-id] "missing"))))))
(deftest punch-range-uses-musical-time
  (is (= 1.0 (daw/punch-duration-seconds p 960)))
  (is (= 0 (daw/seconds->ticks p 0)))
  (is (= [0 0.35 1] (mapv daw/monitor-gain [-1 0.35 4]))))
(deftest validated-project-persistence
  (is (= p (daw/accept-project p)))
  (is (nil? (daw/accept-project (assoc p :project/schema "foreign/v1"))))
  (is (nil? (daw/accept-project [:not :a :project]))))
(deftest versioned-crash-recovery
  (is (= p (daw/recover-project (daw/recovery-envelope p))))
  (is (= {:project p :history daw/empty-history}
         (daw/recover-workspace {:recovery/version 1 :recovery/project p})))
  (is (nil? (daw/recover-project {:recovery/version 999 :recovery/project p})))
  (is (nil? (daw/recover-project {:recovery/version 1 :recovery/project (assoc p :project/schema "foreign/v1")}))))
(deftest persisted-asset-relink-manifest
  (let [registered (daw/register-asset p "audio:t" "take.wav" "abc123")]
    (is (= "audio:t" (daw/asset-id-by-name registered "take.wav")))
    (is (= "audio:t" (daw/asset-id-by-signature registered {:name "renamed.wav" :sha256 "abc123"})))
    (is (nil? (daw/asset-id-by-signature registered {:name "take.wav" :sha256 "different"})))
    (is (= {:asset/name "take.wav" :asset/sha256 "abc123"} (get-in registered [:project/assets "audio:t"])))
    (is (= ["audio:t"] (daw/missing-asset-ids registered [])))
    (is (empty? (daw/missing-asset-ids registered ["audio:t"])))
    (is (= registered (daw/recover-project (daw/recovery-envelope registered))))))
(deftest deterministic-directory-relink-search
  (let [project (-> p (daw/register-asset "hashed" "original.wav" "aaa")
                    (daw/register-asset "legacy" "legacy.wav"))
        candidates [{:file/index 0 :file/path "root/z/renamed.wav" :file/name "renamed.wav" :file/sha256 "aaa"}
                    {:file/index 1 :file/path "root/a/renamed.wav" :file/name "renamed.wav" :file/sha256 "aaa"}
                    {:file/index 2 :file/path "root/legacy.wav" :file/name "legacy.wav" :file/sha256 "new"}
                    {:file/index 3 :file/path "root/original.wav" :file/name "original.wav" :file/sha256 "wrong"}
                    {:file/index 4 :file/path "root/unused.wav" :file/name "unused.wav" :file/sha256 "unused"}]
        plan (daw/directory-relink-plan project candidates)]
    (is (= [["hashed" 1] ["legacy" 2]]
           (mapv (fn [match] [(:asset/id match) (get-in match [:candidate :file/index])]) (:relink/matches plan))))
    (is (empty? (:relink/missing plan)))
    (is (= ["root/original.wav" "root/unused.wav" "root/z/renamed.wav"] (:relink/ignored-paths plan)))))
(deftest bounded-project-undo-redo
  (let [p2 (assoc p :project/name "two") history (daw/record-history daw/empty-history p)
        undone (daw/undo-project p2 history) redone (daw/redo-project (:project undone) (:history undone))]
    (is (= p (:project undone)))
    (is (= p2 (:project redone)))
    (is (= 50 (count (:history/past (reduce (fn [h n] (daw/record-history h (assoc p :n n)))
                                             daw/empty-history (range 70))))))
    (is (= {:project p2 :history history}
           (daw/recover-workspace (daw/recovery-envelope p2 history))))
    (is (nil? (daw/recover-workspace
               (daw/recovery-envelope p2 {:history/past [(assoc p :project/schema "foreign/v1")]
                                          :history/future []}))))
    (is (= 50 (count (:history/past
                      (:history (daw/recover-workspace
                                 (daw/recovery-envelope p2 {:history/past (vec (repeat 70 p))
                                                            :history/future []})))))))))
(deftest portable-media-package-contract
  (let [sha (apply str (repeat 64 "a"))
        packaged (daw/register-asset p "audio:t" "take.wav" sha)
        media {"audio:t" {:entry/name "media/0" :media/name "take.wav" :media/type "audio/wav" :media/sha256 sha}}
        manifest (daw/package-manifest packaged media)]
    (is (= {:project packaged :media media} (daw/accept-package packaged manifest #{"media/0"})))
    (is (nil? (daw/accept-package packaged manifest #{})))
    (is (nil? (daw/accept-package packaged (assoc-in manifest [:package/media "audio:t" :media/sha256]
                                                      (apply str (repeat 64 "b"))) #{"media/0"})))
    (is (= "media/7" (daw/package-entry-name 7)))))
