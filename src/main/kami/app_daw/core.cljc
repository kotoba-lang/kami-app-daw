(ns kami.app-daw.core)

(def schema "kami.ongaku-project/v1")
(defn project [m] (merge {:project/schema schema :project/ppq 480 :project/bpm 120
                           :project/buses [{:bus/id "master" :bus/name "Master" :bus/gain 1.0}]
                           :project/assets {} :project/tracks []} m))
(defn register-asset [p asset-id name]
  (assoc-in p [:project/assets asset-id] {:asset/name name}))
(defn asset-id-by-name [p name]
  (some (fn [[asset-id asset]] (when (= name (:asset/name asset)) asset-id)) (:project/assets p)))
(defn clip-end [clip] (+ (:clip/start-tick clip) (:clip/length-ticks clip)))
(defn duration-ticks [p] (reduce max 0 (map clip-end (mapcat :track/clips (:project/tracks p)))))
(defn tick->seconds [p tick] (/ (* tick 60.0) (* (:project/bpm p) (:project/ppq p))))
(defn seconds->ticks [p seconds]
  (long (#?(:clj Math/round :cljs js/Math.round)
         (/ (* seconds (:project/bpm p) (:project/ppq p)) 60.0))))
(defn punch-duration-seconds [p length-ticks]
  (tick->seconds p (max 1 length-ticks)))
(defn move-clip [p clip-id tick]
  (update p :project/tracks
          (fn [tracks] (mapv (fn [track] (update track :track/clips
            (fn [clips] (mapv #(if (= clip-id (:clip/id %))
                                 (assoc % :clip/start-tick (max 0 tick)) %) clips)))) tracks))))
(defn set-track [p track-id k value]
  (update p :project/tracks #(mapv (fn [track] (if (= track-id (:track/id track))
                                                  (assoc track k value) track)) %)))
(defn update-clip [p clip-id f]
  (update p :project/tracks
          #(mapv (fn [track] (update track :track/clips
                                   (fn [clips] (mapv (fn [clip] (if (= clip-id (:clip/id clip)) (f clip) clip)) clips)))) %)))
(defn edit-clip [p clip-id {:keys [source-offset-sec fade-in-sec fade-out-sec]}]
  (update-clip p clip-id
               #(assoc % :clip/source-offset-sec (max 0 (or source-offset-sec 0))
                         :clip/fade-in-sec (max 0 (or fade-in-sec 0))
                         :clip/fade-out-sec (max 0 (or fade-out-sec 0)))))
(defn set-gain-automation [p track-id points]
  (set-track p track-id :track/gain-automation
             (->> points (mapv (fn [{:keys [tick gain]}]
                                 {:automation/tick (max 0 tick) :automation/gain (max 0 gain)}))
                  (sort-by :automation/tick) vec)))
(defn solo-track-project [p track-id]
  (update p :project/tracks #(vec (filter (fn [track] (= track-id (:track/id track))) %))))
(defn route-track [p track-id bus-id send]
  (-> p (set-track track-id :track/bus-id bus-id)
      (set-track track-id :track/send (max 0 (min 1 send)))))
(defn add-recorded-clip [p track-id asset-id start-tick duration-sec clip-id]
  (let [clip {:clip/id clip-id :clip/name "Recorded take" :clip/asset-id asset-id
              :clip/start-tick (max 0 start-tick)
              :clip/length-ticks (max 1 (seconds->ticks p duration-sec))
              :clip/source-offset-sec 0 :clip/fade-in-sec 0.01 :clip/fade-out-sec 0.03}]
    (update p :project/tracks
            #(mapv (fn [track] (if (= track-id (:track/id track))
                                 (update track :track/clips (fnil conj []) clip) track)) %))))
(defn validate-project [p]
  (vec (concat
        (when-not (= schema (:project/schema p)) [:unsupported-schema])
        (when-not (pos-int? (:project/ppq p)) [:invalid-ppq])
        (when-not (and (number? (:project/bpm p)) (pos? (:project/bpm p))) [:invalid-bpm])
        (for [track (:project/tracks p)
              :let [points (:track/gain-automation track)]
              :when (and (seq points)
                         (not (apply <= (map :automation/tick points))))]
          [:invalid-automation-order (:track/id track)])
        (let [bus-ids (set (map :bus/id (:project/buses p)))]
          (for [track (:project/tracks p)
                :when (and (:track/bus-id track) (not (contains? bus-ids (:track/bus-id track))))]
            [:missing-bus (:track/id track) (:track/bus-id track)]))
        (for [c (mapcat :track/clips (:project/tracks p))
              :when (or (neg? (:clip/start-tick c)) (not (pos-int? (:clip/length-ticks c)))
                        (neg? (or (:clip/source-offset-sec c) 0))
                        (neg? (or (:clip/fade-in-sec c) 0)) (neg? (or (:clip/fade-out-sec c) 0)))]
          [:invalid-clip (:clip/id c)]))))
(defn accept-project [value]
  (when (and (map? value) (empty? (validate-project value))) value))
(def recovery-version 1)
(defn recovery-envelope [p] {:recovery/version recovery-version :recovery/project p})
(defn recover-project [value]
  (when (and (map? value) (= recovery-version (:recovery/version value)))
    (accept-project (:recovery/project value))))
