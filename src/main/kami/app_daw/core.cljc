(ns kami.app-daw.core)

(def schema "kami.ongaku-project/v1")
(def history-limit 50)
(def empty-history {:history/past [] :history/future []})
(defn record-history [history previous]
  {:history/past (->> (conj (vec (:history/past history)) previous) (take-last history-limit) vec)
   :history/future []})
(defn undo-project [current history]
  (if-let [previous (peek (:history/past history))]
    {:project previous :history {:history/past (pop (:history/past history))
                                 :history/future (->> (conj (vec (:history/future history)) current)
                                                      (take-last history-limit) vec)}}
    {:project current :history history}))
(defn redo-project [current history]
  (if-let [next-project (peek (:history/future history))]
    {:project next-project :history {:history/past (->> (conj (vec (:history/past history)) current)
                                                        (take-last history-limit) vec)
                                     :history/future (pop (:history/future history))}}
    {:project current :history history}))
(defn project [m] (merge {:project/schema schema :project/ppq 480 :project/bpm 120
                           :project/buses [{:bus/id "master" :bus/name "Master" :bus/gain 1.0}]
                           :project/assets {} :project/tracks []} m))
(defn register-asset
  ([p asset-id name] (register-asset p asset-id name nil))
  ([p asset-id name sha256]
   (assoc-in p [:project/assets asset-id] (cond-> {:asset/name name} sha256 (assoc :asset/sha256 sha256)))))
(defn asset-id-by-name [p name]
  (some (fn [[asset-id asset]] (when (= name (:asset/name asset)) asset-id)) (:project/assets p)))
(defn asset-id-by-signature [p {:keys [name sha256]}]
  (or (when sha256 (some (fn [[asset-id asset]] (when (= sha256 (:asset/sha256 asset)) asset-id)) (:project/assets p)))
      (asset-id-by-name p name)))
(defn missing-asset-ids [p loaded-ids]
  (->> (keys (:project/assets p)) (remove (set loaded-ids)) sort vec))
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
(def recovery-version 2)
(defn valid-history [history]
  (when (and (map? history)
             (vector? (:history/past history))
             (vector? (:history/future history))
             (every? accept-project (concat (:history/past history) (:history/future history))))
    {:history/past (->> (:history/past history) (take-last history-limit) vec)
     :history/future (->> (:history/future history) (take-last history-limit) vec)}))
(defn recovery-envelope
  ([p] (recovery-envelope p empty-history))
  ([p history] {:recovery/version recovery-version :recovery/project p :recovery/history history}))
(defn recover-workspace [value]
  (when (map? value)
    (case (:recovery/version value)
      1 (when-let [p (accept-project (:recovery/project value))] {:project p :history empty-history})
      2 (when-let [p (accept-project (:recovery/project value))]
          (when-let [history (valid-history (:recovery/history value))]
            {:project p :history history}))
      nil)))
(defn recover-project [value]
  (:project (recover-workspace value)))
