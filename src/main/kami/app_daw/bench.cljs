(ns kami.app-daw.bench)

(def actors
  [{:id "bedroom-producer" :label "Bedroom producer" :tasks ["Create a project" "Record a sound" "Export a WAV"]}
   {:id "mix-engineer" :label "Mix engineer" :tasks ["Route a track" "Automate a parameter" "Print a master"]}
   {:id "live-performer" :label "Live performer" :tasks ["Load a set" "Start monitoring" "Recover from a stop"]}
   {:id "immersive-engineer" :label "Immersive / Atmos engineer" :tasks ["Configure multichannel output" "Check delivery metadata"]}
   {:id "accessibility-user" :label "Accessibility user" :tasks ["Complete a task with keyboard" "Find a focusable control"]}])

(defn initial-run [] {:actor-id (:id (first actors)) :task-index 0 :events [] :started-at (.now js/Date)})
(defn current-task [run] (get-in (some #(when (= (:id %) (:actor-id run)) %) actors) [:tasks (:task-index run)]))
(defn record! [run-atom kind payload] (swap! run-atom update :events conj (merge {:kind kind :at (.now js/Date)} payload)))
