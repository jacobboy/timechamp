(ns timechamp.gc-util-test
  (:require [clojure.test :refer :all]
            [timechamp.gc-util :refer :all])
  (:import  [java.time LocalDateTime ZoneId]
            com.google.api.client.util.DateTime))

(def localdatetime-to-gc-datetime #'timechamp.gc-util/localdatetime-to-gc-datetime)
(def gc-datetime-to-localdatetime #'timechamp.gc-util/gc-datetime-to-localdatetime)

(deftest localdatetime-to-gc-datetime-test
  (testing "LocalDateTime transforms to Google DateTime appropriately"
    (is (= (DateTime. "2011-12-03T10:15:30+01:00")
           (localdatetime-to-gc-datetime (LocalDateTime/of 2011 12 3 10 15 30)
                                         (ZoneId/of "+1"))))))

(deftest gc-datetime-to-localdatetime-test
  (testing "Google DateTime transforms to LocalDateTime appropriately"
    (is (= (LocalDateTime/of 2003 10 21 13 36)
           (gc-datetime-to-localdatetime (DateTime. "2003-10-21T13:36:00-07:00"))))))
