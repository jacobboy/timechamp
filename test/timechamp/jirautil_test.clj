(ns timechamp.jirautil-test
  (:require [clojure.test :refer :all]
            [timechamp.config :as config]
            [timechamp.jirautil :as sut]))

(def get-tracking-tc-ids #'sut/get-tracking-tc-ids)

(deftest test-get-tracking-tc-ids
  (testing "An empty list is returned when no tasks are tracked"
    (with-redefs [sut/board-and-status-name (fn [] ["board" "status"])
                  sut/get-board-id (fn [board-name] 12345)
                  sut/get-current-sprint-id (fn [board-id] 67890)
                  sut/get-user-name (fn [] "user name")
                  config/get-config-values (fn [& args] [{(keyword "MED: Bug fixes") 9238865}])
                  sut/make-request (fn [& args] {})]
      (let [resp (get-tracking-tc-ids)]
        (is (= resp []))))))

(deftest test-expand-jira-tuple
  (testing "Jira tuples are expanded correctly"
    (testing "with no jira ids"
      (with-redefs [sut/get-tracking-tc-ids (fn [] '())]
        (let [tuple ["jira" 0.4]
              expanded (sut/expand-jira-tuple tuple)]
          (is (= expanded [])))))
    (testing "with one jira id"
      (with-redefs [sut/get-tracking-tc-ids (fn [] '(2))]
        (let [tuple ["jira" 0.4]
              expanded (sut/expand-jira-tuple tuple)]
          (is (= expanded [[2 0.4]])))))
    (testing "with multiple jira ids"
      (with-redefs [sut/get-tracking-tc-ids (fn [] '(2 3))]
        (let [tuple ["jira" 0.4]
              expanded (sut/expand-jira-tuple tuple)]
          (is (= expanded [[2 0.2] [3 0.2]])))))))
