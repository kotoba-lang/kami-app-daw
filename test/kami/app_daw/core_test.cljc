(ns kami.app-daw.core-test (:require [clojure.test :refer [deftest is]] [kami.app-daw.core :as daw]))
(def p (daw/project {:project/tracks [{:track/id "t" :track/clips [{:clip/id "c" :clip/start-tick 0 :clip/length-ticks 960}]}]}))
(deftest transport-and-edit (is (= 960 (daw/duration-ticks p))) (is (= 1.0 (daw/tick->seconds p 960)))
  (is (= 240 (get-in (daw/move-clip p "c" 240) [:project/tracks 0 :track/clips 0 :clip/start-tick])))
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
(deftest bus-routing
  (let [routed (daw/route-track p "t" "master" 0.35)]
    (is (= ["master" 0.35] ((juxt :track/bus-id :track/send) (get-in routed [:project/tracks 0]))))
    (is (empty? (daw/validate-project routed)))
    (is (= [[:missing-bus "t" "missing"]]
           (daw/validate-project (daw/route-track p "t" "missing" 0.1))))))
(deftest recorded-take-becomes-authoritative-clip
  (let [recorded (daw/add-recorded-clip p "t" "recording:1" 480 1.25 "take-1")
        clip (last (get-in recorded [:project/tracks 0 :track/clips]))]
    (is (= ["take-1" "recording:1" 480 1200]
           ((juxt :clip/id :clip/asset-id :clip/start-tick :clip/length-ticks) clip)))
    (is (empty? (daw/validate-project recorded)))))
(deftest punch-range-uses-musical-time
  (is (= 1.0 (daw/punch-duration-seconds p 960)))
  (is (= 0 (daw/seconds->ticks p 0))))
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
    (is (= {:asset/name "take.wav" :asset/sha256 "abc123"} (get-in registered [:project/assets "audio:t"])))
    (is (= ["audio:t"] (daw/missing-asset-ids registered [])))
    (is (empty? (daw/missing-asset-ids registered ["audio:t"])))
    (is (= registered (daw/recover-project (daw/recovery-envelope registered))))))
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
