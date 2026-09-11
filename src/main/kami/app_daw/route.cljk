(ns kami.app-daw.route
  "This app's addressable views. The routing itself is `kotoba-lang/route`.

  What is left here is the only part that was ever app-specific: the table. The
  ~76 lines that resolved a fragment, generated a nav and tracked the address bar
  were identical in `kami-app-daw` and `kami-app-nle` to within one word of a
  docstring, and `kami-app-suji` made three — the extraction trigger the
  `kotoba-uiux` skill names."
  (:require [route.core :as route]
            #?(:cljs [route.reagent :as route-reagent])))

(def views
  "Every view this app has, in nav order. The first is the default: the fragment
  is empty on a fresh visit, and an unknown fragment resolves here."
  (route/validate!
   [{:id :studio :fragment "#/" :label "Studio"}
   {:id :user-test :fragment "#/user-test" :label "User test"}]))

(def default-view (first views))

(defn fragment->view [fragment] (route/fragment->view views fragment))
(defn nav [active-id] (route/nav views active-id))

#?(:cljs
   (do
     (defonce ^:private tracker (route-reagent/make-tracker views))
     (def current (:current tracker))
     (defn install! [] ((:install! tracker)))))
