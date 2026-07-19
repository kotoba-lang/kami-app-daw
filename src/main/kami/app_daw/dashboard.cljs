(ns kami.app-daw.dashboard
  (:require [reagent.core :as r] [reagent.dom.client :as rdom] [html.core :as html]))
(defonce sessions (r/atom []))
(defn read-file! [file] (.then (.text file) #(swap! sessions conj (js->clj (js/JSON.parse %) :keywordize-keys true))))
(defn row [s] (let [results (filter #(= :task-result (:kind %)) (:events s)) passed (count (filter :success? results))] [:tr (for [[k v] [[:session-id (:session-id s)] [:source (:source s)] [:build (:build s)] [:passed passed] [:failed (- (count results) passed)] [:errors (reduce + 0 (map #(or (:errors %) 0) results))]]] ^{:key k} [:td (str v)])]))
(defn app [] [:main.liquid-glass [:header.liquid-glass__toolbar [:h1 "KAMI DAW · User-test dashboard"] [:small "kotoba-lang/html · Reagent · Hiccup"]] [:section.liquid-glass__panel [:p "Import JSON artifacts exported by actor sessions."] [:input {:type "file" :multiple true :accept "application/json" :on-change #(doseq [f (array-seq (.. % -target -files))] (read-file! f))}] [:table [:thead [:tr (for [h ["Session" "Source" "Build" "Passed" "Failed" "Errors"]] ^{:key h} [:th h])]] [:tbody (for [s @sessions] ^{:key (:session-id s)} [row s])]]]] )
(def contract (html/html [:meta {:name "kotoba:app-shell" :content "kami-daw user-test dashboard"}] [:noscript "Enable JavaScript to compare sessions."]))
(defonce root (atom nil))
(defn init! [] (when-not @root (set! (.-innerHTML (.querySelector js/document "head")) (str contract)) (reset! root (rdom/create-root (.getElementById js/document "app")))) (rdom/render @root [app]))
