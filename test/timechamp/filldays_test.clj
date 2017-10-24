(ns timechamp.filldays-test
  (:require [clojure.test :refer :all]
            [timechamp.filldays :as sut]
            [timechamp.jirautil :as jirautil])
  (:import java.time.LocalDate))

(def weekend-filter #'sut/weekend-filter)

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

(def task-id->time-from-arguments #'sut/task-id->time-from-arguments)

(deftest test-task-id->time-from-arguments
  (testing "Creates the task-id->time map correctly"
    (testing "without a jira argument"
      (with-redefs [jirautil/get-tracking-tc-ids (fn [] '(2 3))]
        (let [args [1 "1h30m"]
              {:keys [task-id->minutes
                      task-id->pcts]} (task-id->time-from-arguments args)]
          (is (= task-id->minutes {1 90})
              (= task-id->pcts {})))))
    (testing "with a jira argument"
      (with-redefs [jirautil/get-tracking-tc-ids (fn [] '(2 3))]
        (let [args [1 "1h30m" "jira" "40%"]
              {:keys [task-id->minutes
                      task-id->pcts]} (task-id->time-from-arguments args)]
          (is (= task-id->minutes {1 90})
              (= task-id->pcts {2 0.2 3 0.2})))))
    (testing "with a jira argument but no jira TC ids"
      (with-redefs [jirautil/get-tracking-tc-ids (fn [] '())]
        (let [args [1 "1h30m" "jira" "40%"]
              {:keys [task-id->minutes
                      task-id->pcts]} (task-id->time-from-arguments args)]
          (is (= task-id->minutes {1 90})
              (= task-id->pcts {})))))))
