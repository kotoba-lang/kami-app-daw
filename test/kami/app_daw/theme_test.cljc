(ns kami.app-daw.theme-test
  "Locks the property the design-system migration bought: the app states its
  colors and type once, in a theme map, and everything else references tokens."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kotoba-ui.core :as ui]
            [kami.app-daw.theme :as theme]))

(deftest app-css-has-no-raw-values-test
  (testing "no raw hex color in the app stylesheet"
    ;; public/style.css carried 18 of them, plus its own font stack and its
    ;; own dark palette. The accent below is the only hex the app may state.
    (is (theme/hex-free? theme/app-css)
        (str "leaked: " (re-find #"#[0-9a-fA-F]{3,8}" theme/app-css))))
  (testing "no hand-written font stack: type comes from the HIG tokens"
    ;; What the old style.css had, and what this forbids, is
    ;; `font-family:Inter,system-ui,sans-serif`. A token reference is fine.
    (doseq [[_ v] (re-seq #"font-family\s*:\s*([^;}]+)" theme/app-css)]
      (is (str/includes? v "var(--hig-font-") (str "hand-written font stack: " v))))
  (testing "no hardcoded color-scheme: appearance comes from the theme map"
    (is (not (str/includes? theme/app-css "color-scheme"))))
  (testing "every color reference is a token"
    (let [colors (re-seq #"(?:color|background)\s*:\s*([^;}]+)" theme/app-css)]
      (is (seq colors))
      (doseq [[_ v] colors]
        (is (or (str/includes? v "var(--hig-")
                (str/includes? v "transparent"))
            (str "non-token color value: " v))))))

(deftest theme-is-the-single-hex-test
  (testing "the theme map is where a hex legitimately lives (agent-guide rule 5)"
    (is (= "#67e8f9" (:accent theme/theme)))
    (is (= :dark (:appearance theme/theme))))
  (testing "and it reaches the emitted stylesheet as the tint"
    (let [css (theme/stylesheet)]
      (is (str/includes? css "--hig-color-tint: #67e8f9"))
      (is (str/includes? css "@layer")))))

(deftest track-colors-come-from-the-system-palette-test
  (testing "track color is content, but sourced from the HIG palette"
    ;; The sample project used to invent #ff8a65 / #c4b5fd — hues that appear
    ;; nowhere else in the workspace and have no dark-appearance counterpart.
    (is (every? #(str/starts-with? % "var(--hig-palette-") theme/track-palette))
    (is (theme/hex-free? (str/join " " theme/track-palette))))
  (testing "it cycles rather than running out"
    (is (= (theme/track-color 0) (theme/track-color (count theme/track-palette))))
    (is (apply distinct? (map theme/track-color (range (count theme/track-palette)))))))

(deftest stylesheet-is-the-live-library-test
  (testing "the bundle is composed from the pinned kotoba-ui, not a frozen copy"
    ;; public/liquid-glass.css was a 39 KB artifact checked in and never
    ;; refreshed — byte-identical in kami-app-nle, and carrying zero --hig-*
    ;; tokens. Composing it here means the app follows the library.
    (let [css (theme/stylesheet)]
      (is (str/includes? css "liquid-glass__button"))
      (is (str/includes? css "--hig-color-label"))
      (is (str/includes? css "kotoba-shell__app"))
      (is (str/includes? css (str/trim theme/app-css))))))

(deftest page-is-generated-test
  (testing "->page supplies what the hand-written index.html had to restate"
    (let [html (ui/->page {:title "KAMI DAW" :theme theme/theme} [:div {:id "app"}])]
      (is (str/starts-with? html "<!doctype html>"))
      (is (str/includes? html "data-appearance=\"dark\""))
      (is (str/includes? html "viewport-fit=cover"))
      (is (str/includes? html "name=\"theme-color\"")))))
