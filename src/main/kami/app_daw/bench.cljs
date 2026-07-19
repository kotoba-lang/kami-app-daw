(ns kami.app-daw.bench
  (:require [cljs.reader :as reader]))
(def actors
  [{:id "bedroom-producer" :kind :human :label "Bedroom producer" :criteria {:completion 1 :max-errors 3} :tasks ["Create a project" "Record a sound" "Export a WAV"]}
   {:id "mix-engineer" :kind :expert :label "Mix engineer" :criteria {:completion 1 :max-errors 2} :tasks ["Route a track" "Automate a parameter" "Print a master"]}
   {:id "live-performer" :kind :human :label "Live performer" :criteria {:completion 1 :max-errors 2} :tasks ["Load a set" "Start monitoring" "Recover from a stop"]}
   {:id "immersive-engineer" :kind :expert :label "Immersive / Atmos engineer" :criteria {:completion 1 :max-errors 1} :tasks ["Configure multichannel output" "Check delivery metadata"]}
   {:id "accessibility-user" :kind :human :label "Accessibility user" :criteria {:completion 1 :max-errors 2} :tasks ["Complete a task with keyboard" "Find a focusable control"]}
   {:id "synthetic-agent" :kind :synthetic :label "Synthetic preflight agent" :criteria {:completion 1 :max-errors 0} :tasks ["Run smoke scenario" "Check recovery"]}])
(def storage-key "kami-daw/user-test/v2")
(defn now [] (.now js/Date))
(defn initial-run [] {:schema "kami.user-test/v2" :session-id (str "daw-" (now)) :actor-id (:id (first actors)) :task-index 0 :events [] :started-at (now) :task-started-at (now) :build "local" :source :human})
(defn actor [run] (some #(when (= (:id %) (:actor-id run)) %) actors))
(defn current-task [run] (get-in (actor run) [:tasks (:task-index run)]))
(defn record! [run-atom kind payload] (swap! run-atom update :events conj (merge {:kind kind :at (now) :task (current-task @run-atom)} payload)))
(defn persist! [run] (.setItem js/localStorage storage-key (pr-str run)) run)
(defn restore [] (try (some-> (.getItem js/localStorage storage-key) reader/read-string) (catch :default _ nil)))
(defn complete-task! [run-atom success? errors] (let [run @run-atom] (record! run-atom :task-result {:success? success? :errors (or errors 0) :duration-ms (- (now) (:task-started-at run))}) (swap! run-atom update :task-index #(min (dec (count (:tasks (actor run)))) (inc %))) (swap! run-atom assoc :task-started-at (now)) (persist! @run-atom))
(defn feedback! [run-atom text severity rating] (record! run-atom :feedback {:text text :severity severity :rating rating}) (persist! @run-atom))
(defn summary [run] (let [results (filter #(= :task-result (:kind %)) (:events run))] {:passed (count (filter :success? results)) :failed (count (remove :success? results)) :errors (reduce + 0 (map #(or (:errors %) 0) results)) :avg-duration-ms (when (seq results) (/ (reduce + (map :duration-ms results)) (count results))) :source (:source run) :build (:build run)}))
(defn export! [run] (let [blob (js/Blob. #js [(pr-str run)] #js {:type "application/edn"}) url (js/URL.createObjectURL blob) a (.createElement js/document "a")] (set! (.-href a) url) (set! (.-download a) (str (:session-id run) ".edn")) (.click a) (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn export-json! [run] (let [blob (js/Blob. #js [(js/JSON.stringify (clj->js run) nil 2)] #js {:type "application/json"}) url (js/URL.createObjectURL blob) a (.createElement js/document "a")] (set! (.-href a) url) (set! (.-download a) (str (:session-id run) ".json")) (.click a) (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
