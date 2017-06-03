(ns timecop.core-test
  (:require [clojure.test :refer :all]
            [timecop.core :refer :all]))

(deftest test-date-validation
  (testing "Input dates are properly validated"
    (testing "A good date passes"
      (is (= true
             (yyyy-MM-dd? "2017-05-09"))))
    (testing "A bad date fails"
      (is (= false
             (yyyy-MM-dd? "20170509"))))))
