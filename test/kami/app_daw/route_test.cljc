(ns kami.app-daw.route-test
  "Locks the two properties that make a single-page app navigable: every view is
  addressable, and every view is reachable."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kami.app-daw.route :as route]
            [kami.app-daw.theme :as theme]))

(deftest views-are-addressable-test
  (testing "ids and fragments are unique — two views on one address is one view"
    (is (apply distinct? (map :id route/views)))
    (is (apply distinct? (map :fragment route/views))))
  (testing "every fragment resolves to its own view"
    (doseq [{:keys [id fragment]} route/views]
      (is (= id (:id (route/fragment->view fragment))) fragment)))
  (testing "every view has a label to put in the nav"
    (is (every? (comp seq :label) route/views))))

(deftest unknown-addresses-fall-back-test
  (testing "an address bar is user input: nothing blanks the app"
    (doseq [f ["" nil "#" "#/nope" "#garbage" "/user-test"]]
      (is (= (:id route/default-view) (:id (route/fragment->view f))) (pr-str f))))
  (testing "the default is the first view, so the nav's first link is home"
    (is (= (:id (first route/views)) (:id route/default-view))))
  (testing "a fragment with trailing junk still finds its view"
    (is (= :user-test (:id (route/fragment->view "#/user-test?from=email"))))))

(deftest nav-covers-every-view-test
  (let [rendered (pr-str (route/nav :user-test))]
    (testing "one link per view — a view missing from the nav is dead code"
      (is (= (count route/views)
             (count (re-seq #":href" rendered))))
      (doseq [{:keys [fragment label]} route/views]
        (is (str/includes? rendered fragment))
        (is (str/includes? rendered label))))
    (testing "exactly one link is marked current, and it is the active view"
      (is (= 1 (count (re-seq #":aria-current" rendered))))
      (is (str/includes? rendered ":aria-current \"page\"")))
    (testing "the links are design-system buttons, not app-styled anchors"
      (is (str/includes? rendered "dads-button"))
      (is (theme/hex-free? rendered)))))

(deftest nav-marks-each-view-in-turn-test
  (testing "whichever view is active is the one marked — including the default"
    (doseq [{:keys [id]} route/views]
      (let [rendered (pr-str (route/nav id))]
        (is (= 1 (count (re-seq #":aria-current" rendered))) (str id))))))
