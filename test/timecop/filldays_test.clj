(ns timecop.filldays-test
  (:require [clojure.test :refer :all]
            [timecop.filldays :as filldays])
  (:import java.time.LocalDate))

(def weekend-filter #'filldays/weekend-filter)

(deftest weekend-filter-test
  (testing "Filter weekend days when required"
    (let [keep (weekend-filter true)
          remove (weekend-filter false)]
      (is (true? (keep (LocalDate/of 2017 6 24))))
      (is (true? (keep (LocalDate/of 2017 6 25))))
      (is (true? (keep (LocalDate/of 2017 6 26))))
      (is (false? (remove (LocalDate/of 2017 6 24))))
      (is (false? (remove (LocalDate/of 2017 6 25))))
      (is (true? (remove (LocalDate/of 2017 6 26)))))))
