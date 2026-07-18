(ns kami.app-daw.core)

(def schema "kami.ongaku-project/v1")
(defn project [m] (merge {:project/schema schema :project/ppq 480 :project/bpm 120
                           :project/tracks []} m))
(defn clip-end [clip] (+ (:clip/start-tick clip) (:clip/length-ticks clip)))
(defn duration-ticks [p] (reduce max 0 (map clip-end (mapcat :track/clips (:project/tracks p)))))
(defn tick->seconds [p tick] (/ (* tick 60.0) (* (:project/bpm p) (:project/ppq p))))
(defn move-clip [p clip-id tick]
  (update p :project/tracks
          (fn [tracks] (mapv (fn [track] (update track :track/clips
            (fn [clips] (mapv #(if (= clip-id (:clip/id %))
                                 (assoc % :clip/start-tick (max 0 tick)) %) clips)))) tracks))))
(defn set-track [p track-id k value]
  (update p :project/tracks #(mapv (fn [track] (if (= track-id (:track/id track))
                                                  (assoc track k value) track)) %)))
(defn validate-project [p]
  (vec (concat
        (when-not (= schema (:project/schema p)) [:unsupported-schema])
        (when-not (pos-int? (:project/ppq p)) [:invalid-ppq])
        (when-not (and (number? (:project/bpm p)) (pos? (:project/bpm p))) [:invalid-bpm])
        (for [c (mapcat :track/clips (:project/tracks p))
              :when (or (neg? (:clip/start-tick c)) (not (pos-int? (:clip/length-ticks c))))]
          [:invalid-clip (:clip/id c)]))))
