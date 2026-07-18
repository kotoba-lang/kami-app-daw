(ns kami.app-daw.core-test (:require [clojure.test :refer [deftest is]] [kami.app-daw.core :as daw]))
(def p (daw/project {:project/tracks [{:track/id "t" :track/clips [{:clip/id "c" :clip/start-tick 0 :clip/length-ticks 960}]}]}))
(deftest transport-and-edit (is (= 960 (daw/duration-ticks p))) (is (= 1.0 (daw/tick->seconds p 960)))
  (is (= 240 (get-in (daw/move-clip p "c" 240) [:project/tracks 0 :track/clips 0 :clip/start-tick])))
  (is (zero? (get-in (daw/move-clip p "c" -240) [:project/tracks 0 :track/clips 0 :clip/start-tick])))
  (is (empty? (daw/validate-project p))))
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
