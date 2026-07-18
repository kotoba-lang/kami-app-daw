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
      (some (fn [[asset-id asset]] (when (and (= name (:asset/name asset)) (nil? (:asset/sha256 asset))) asset-id))
            (:project/assets p))))
(defn directory-relink-plan [p candidates]
  (let [ordered (sort-by (juxt :file/path :file/name :file/index) candidates)
        matches (->> (:project/assets p)
                     (sort-by key)
                     (keep (fn [[asset-id asset]]
                             (when-let [candidate
                                        (if-let [expected (:asset/sha256 asset)]
                                          (first (filter #(= expected (:file/sha256 %)) ordered))
                                          (first (filter #(= (:asset/name asset) (:file/name %)) ordered)))]
                               {:asset/id asset-id :candidate candidate})))
                     vec)
        matched-ids (set (map :asset/id matches))
        used-indexes (set (map (comp :file/index :candidate) matches))]
    {:relink/matches matches
     :relink/missing (->> (keys (:project/assets p)) (remove matched-ids) sort vec)
     :relink/ignored-paths (->> ordered (remove #(contains? used-indexes (:file/index %))) (map :file/path) vec)}))
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
(defn monitor-gain [gain] (max 0 (min 1 (or gain 0))))
(defn power->lufs [power]
  (if (pos? power)
    (+ -0.691 (* 10 (#?(:clj Math/log10 :cljs js/Math.log10) power)))
    -96.0))
(defn integrated-lufs [block-powers]
  (let [absolute (filter #(> (power->lufs %) -70) block-powers)]
    (if (empty? absolute) -96.0
        (let [preliminary (power->lufs (/ (reduce + absolute) (count absolute)))
              relative-gate (- preliminary 10)
              gated (filter #(> (power->lufs %) relative-gate) absolute)]
          (if (seq gated) (power->lufs (/ (reduce + gated) (count gated))) -96.0)))))
(defn normalization-gain-db [measured-lufs true-peak-db target-lufs ceiling-db]
  (if (<= measured-lufs -95)
    0.0
    (min (- target-lufs measured-lufs) (- ceiling-db true-peak-db))))
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
(defn set-send-automation [p track-id points]
  (set-track p track-id :track/send-automation
             (->> points (mapv (fn [{:keys [tick send]}]
                                 {:automation/tick (max 0 tick) :automation/send (max 0 (min 1 send))}))
                  (sort-by :automation/tick) vec)))
(defn set-bus-gain-automation [p bus-id points]
  (update p :project/buses
          #(mapv (fn [bus]
                   (if (= bus-id (:bus/id bus))
                     (assoc bus :bus/gain-automation
                            (->> points (mapv (fn [{:keys [tick gain]}]
                                               {:automation/tick (max 0 tick) :automation/gain (max 0 gain)}))
                                 (sort-by :automation/tick) vec))
                     bus)) %)))
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
(defn add-comp-take [p track-id comp-id asset-id start-tick duration-sec clip-id take-index]
  (let [clip {:clip/id clip-id :clip/name (str "Take " take-index) :clip/asset-id asset-id
              :clip/start-tick (max 0 start-tick)
              :clip/length-ticks (max 1 (seconds->ticks p duration-sec))
              :clip/source-offset-sec 0 :clip/fade-in-sec 0.01 :clip/fade-out-sec 0.03
              :clip/comp-id comp-id :clip/take-index take-index}]
    (update p :project/tracks
            (fn [tracks] (mapv (fn [track]
                     (if (= track-id (:track/id track))
                       (let [groups (vec (or (:track/take-lanes track) []))
                             found? (some (fn [group] (= comp-id (:comp/id group))) groups)
                             groups' (if found?
                                       (mapv (fn [group]
                                               (if (= comp-id (:comp/id group))
                                                 (-> group (update :comp/takes (fnil conj []) clip)
                                                     (assoc :comp/active-take-id clip-id))
                                                 group)) groups)
                                       (conj groups {:comp/id comp-id :comp/active-take-id clip-id :comp/takes [clip]}))]
                         (-> track
                             (assoc :track/take-lanes groups')
                             (update :track/clips (fn [clips]
                                                    (conj (vec (remove #(= comp-id (:clip/comp-id %)) clips)) clip)))))
                       track)) tracks)))))
(defn select-comp-take [p track-id comp-id clip-id]
  (update p :project/tracks
          (fn [tracks] (mapv (fn [track]
                   (if (= track-id (:track/id track))
                     (if-let [take (some (fn [group]
                                          (when (= comp-id (:comp/id group))
                                            (some (fn [clip] (when (= clip-id (:clip/id clip)) clip)) (:comp/takes group))))
                                        (:track/take-lanes track))]
                       (-> track
                           (update :track/take-lanes
                                   (fn [groups] (mapv (fn [group] (if (= comp-id (:comp/id group))
                                                                    (assoc group :comp/active-take-id clip-id) group)) groups)))
                           (update :track/clips
                                   (fn [clips] (conj (vec (remove #(= comp-id (:clip/comp-id %)) clips)) take))))
                       track)
                     track)) tracks))))
(defn invalid-clip? [clip]
  (or (neg? (:clip/start-tick clip)) (not (pos-int? (:clip/length-ticks clip)))
      (neg? (or (:clip/source-offset-sec clip) 0))
      (neg? (or (:clip/fade-in-sec clip) 0)) (neg? (or (:clip/fade-out-sec clip) 0))))
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
        (for [track (:project/tracks p)
              :let [points (:track/send-automation track)]
              :when (and (seq points) (not (apply <= (map :automation/tick points))))]
          [:invalid-send-automation-order (:track/id track)])
        (for [bus (:project/buses p)
              :let [points (:bus/gain-automation bus)]
              :when (and (seq points) (not (apply <= (map :automation/tick points))))]
          [:invalid-bus-automation-order (:bus/id bus)])
        (let [bus-ids (set (map :bus/id (:project/buses p)))]
          (for [track (:project/tracks p)
                :when (and (:track/bus-id track) (not (contains? bus-ids (:track/bus-id track))))]
            [:missing-bus (:track/id track) (:track/bus-id track)]))
        (for [c (mapcat :track/clips (:project/tracks p))
              :when (invalid-clip? c)]
          [:invalid-clip (:clip/id c)])
        (for [track (:project/tracks p) group (:track/take-lanes track) take (:comp/takes group)
              :when (invalid-clip? take)]
          [:invalid-comp-take (:track/id track) (:comp/id group) (:clip/id take)])
        (for [track (:project/tracks p) group (:track/take-lanes track)
              :when (not (some #(= (:comp/active-take-id group) (:clip/id %)) (:comp/takes group)))]
          [:missing-active-comp-take (:track/id track) (:comp/id group)]))))
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
(def package-version 1)
(def package-max-bytes (* 512 1024 1024))
(defn package-entry-name [index] (str "media/" index))
(defn package-manifest [p media]
  {:package/version package-version :package/project-schema (:project/schema p) :package/media media})
(defn accept-package [p manifest entry-names]
  (let [media (:package/media manifest)
        asset-ids (set (keys (:project/assets p)))]
    (when (and (accept-project p)
               (= package-version (:package/version manifest))
               (= schema (:package/project-schema manifest))
               (map? media)
               (= asset-ids (set (keys media)))
               (= (count media) (count (set (map (comp :entry/name val) media))))
               (every? (fn [[asset-id descriptor]]
                         (and (boolean (re-matches #"media/[0-9]+" (:entry/name descriptor)))
                              (contains? entry-names (:entry/name descriptor))
                              (string? (:media/name descriptor))
                              (string? (:media/type descriptor))
                              (boolean (re-matches #"[0-9a-f]{64}" (:media/sha256 descriptor)))
                              (let [expected (get-in p [:project/assets asset-id :asset/sha256])]
                                (or (nil? expected) (= expected (:media/sha256 descriptor))))))
                       media))
      {:project p :media media})))
