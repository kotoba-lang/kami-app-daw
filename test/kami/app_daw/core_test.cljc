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
