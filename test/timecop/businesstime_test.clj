(ns timecop.businesstime-test
  (:require [clojure.test :refer :all]
            [clojure.test :as t]
            [schema.core :as s]
            [timecop.businesstime :refer :all]
            [timecop.schema :refer :all])
  (:import com.google.api.client.util.DateTime
           [com.google.api.services.calendar.model Event EventDateTime]
           java.time.LocalDateTime))

(def mock-gc-event-simple
  (-> (Event.)
      (.setStart (.setDateTime (EventDateTime.) (DateTime. "2017-05-10T10:15:00.000-04:00")))
      (.setEnd (.setDateTime (EventDateTime.) (DateTime. "2017-05-10T10:30:00.000-04:00")))
      (.setSummary "Review Preparation")
      (.setId "Simple ID")))

(def mock-tc-events-simple
  [{:date "2017-05-10"
    :start_time "10:15:00"
    :end_time "10:30:00"
    :note "summary: Review Preparation\nexternal-id: Simple ID\n",
    :task_id "9238867"
    }])

(def mock-canonical-event-simple
  (let [start-time (LocalDateTime/of 2017 5 10 10 15 0)
        end-time (LocalDateTime/of 2017 5 10 10 30 0)
        description "Review Preparation"
        source :gc
        source-id "Simple ID"
        task-type :meeting]
    (->CanonicalEvent start-time end-time description source source-id task-type)))

(def mock-gc-event-overnight
  (-> (Event.)
      (.setStart (.setDateTime (EventDateTime.) (DateTime. "2017-05-10T22:00:00.000-04:00")))
      (.setEnd (.setDateTime (EventDateTime.) (DateTime. "2017-05-11T18:00:00.000-04:00")))
      (.setSummary "Sous vide roast")
      (.setId "overnight ID")))

(def mock-tc-events-overnight
  [{:date "2017-05-10"
    :start_time "22:00:00"
    :end_time "23:59:59"
    :duration (str (- (* 2 60 60) 1))
    :note "summary: Sous vide roast\nexternal-id: overnight ID\n"
    :task_id "9238867"
    }
   {:date "2017-05-11"
    :start_time "00:00:00"
    :end_time "18:00:00"
    :duration (str (* 18 60 60))
    :note "summary: Sous vide roast\nexternal-id: overnight ID\n"
    :task_id "9238867"}])

(def mock-canonical-event-overnight-whole
  (let [start-time (LocalDateTime/of 2017 5 10 22 0)
        end-time (LocalDateTime/of 2017 5 11 18 0)
        description "Sous vide roast"
        source :gc
        source-id "overnight ID"
        task-type :meeting]
    (->CanonicalEvent start-time end-time description source source-id task-type)))

(def mock-canonical-events-overnight-split
  (let [start-time (LocalDateTime/of 2017 5 10 22 0)
        end-time (LocalDateTime/of 2017 5 10 23 59 59)
        start-time-2 (LocalDateTime/of 2017 5 11 0 0)
        end-time-2 (LocalDateTime/of 2017 5 11 18 0)
        description "Sous vide roast"
        source :gc
        source-id "overnight ID"
        task-type :meeting]
    [(->CanonicalEvent start-time end-time description source source-id task-type)
     (->CanonicalEvent start-time-2 end-time-2 description source source-id task-type)]))

(def mock-gc-event-over-two-nights
  (-> (Event.)
      (.setStart (.setDateTime (EventDateTime.) (DateTime. "2017-05-10T22:00:00.000-04:00")))
      (.setEnd (.setDateTime (EventDateTime.) (DateTime. "2017-05-12T08:00:00.000-04:00")))
      (.setSummary "Sous vide brisket")
      (.setId "over 2 nights ID")))

(def mock-tc-events-over-two-nights
  [{:date "2017-05-10"
    :start_time "22:00:00"
    :end_time "23:59:59"
    :duration (str (- (* 2 60 60) 1))
    :note "summary: Sous vide brisket\nexternal-id: over 2 nights ID\n"
    :task_id "9238867"
    }
   {:date "2017-05-11"
    :start_time "00:00:00"
    :end_time "23:59:59"
    :duration (str (- (* 24 60 60) 1))
    :note "summary: Sous vide brisket\nexternal-id: over 2 nights ID\n"
    :task_id "9238867"
    }
   {:date "2017-05-12"
    :start_time "00:00:00"
    :end_time "08:00:00"
    :duration (str (* 8 60 60))
    :note "summary: Sous vide brisket\nexternal-id: over 2 nights ID\n"
    :task_id "9238867"
    }])

(def mock-canonical-event-over-two-nights-whole
  (map->CanonicalEvent {:start-time (LocalDateTime/of 2017 5 10 22 0)
                        :end-time (LocalDateTime/of 2017 5 12 8 0)
                        :description "Sous vide brisket"
                        :source :gc
                        :source-id "over 2 nights ID"
                        :task-type :meeting}))

(def mock-canonical-events-over-two-nights-split
  (let [start-time (LocalDateTime/of 2017 5 10 22 0)
        end-time (LocalDateTime/of 2017 5 10 23 59 59)
        start-time-2 (LocalDateTime/of 2017 5 11 0 0)
        end-time-2 (LocalDateTime/of 2017 5 11 23 59 59)
        start-time-3 (LocalDateTime/of 2017 5 12 0 0)
        end-time-3 (LocalDateTime/of 2017 5 12 8 0)
        description "Sous vide brisket"
        source :gc
        source-id "over 2 nights ID"
        task-type :meeting]
    [(->CanonicalEvent start-time end-time description source source-id task-type)
     (->CanonicalEvent start-time-2 end-time-2 description source source-id task-type)
     (->CanonicalEvent start-time-3 end-time-3 description source source-id task-type)]))

(deftest beginning-of-next-day-test
  (testing "Returns 00:00:00:000 on the next day"
    (let [beginning-of-next-day #'timecop.businesstime/beginning-of-next-day]
      (is (= (LocalDateTime/of 2015 9 1 0 0)
             (beginning-of-next-day (LocalDateTime/of 2015 8 31 4 15)))))))

(deftest end-of-day-test
  (testing "Returns 23:59:59 on the same day"
    (let [end-of-day #'timecop.businesstime/end-of-day]
      (is (= (LocalDateTime/of 2019 12 31 23 59 59)
             (end-of-day (LocalDateTime/of 2019 12 31 0 1)))))))

(deftest split-event-at-midnight-test
  (testing "Properly splitting at midnight"
    (testing "with a simple canonical event"
      (is (= [mock-canonical-event-simple]
             (split-event-at-midnight mock-canonical-event-simple))))
    (testing "with a canonical event across multiple midnights"
      (is (= mock-canonical-events-over-two-nights-split
             (split-event-at-midnight mock-canonical-event-over-two-nights-whole))))
    (testing "with an overnight event is split into two by split-event-at-midnight"
      (is (= mock-canonical-events-overnight-split
             (split-event-at-midnight mock-canonical-event-overnight-whole))))))

(deftest hours-to-minutes-test
  (testing
      "Transform input hours-and-minutes strings into the number of minutes"
    (is (= 0 (hours-to-minutes "1h30")))
    (is (= 0 (hours-to-minutes "0h0m")))
    (is (= 90 (hours-to-minutes "1h30m")))
    (is (= 90 (hours-to-minutes "90m")))
    (is (= 120 (hours-to-minutes "1h60m")))
    (is (= 261 (hours-to-minutes "2.25h10.9m")))))

(deftest hours-to-minutes-test
  (testing "Transform input percent strings into a number"
    (is (= 0.0 (pct-strs-to-num "30")))
    (is (= 0.0 (pct-strs-to-num "0%")))
    (is (= 0.3 (pct-strs-to-num "30%")))
    (is (= 0.3012 (pct-strs-to-num "30.12%")))))

(deftest task-id->minutes-from-pcts-test
  (testing "Test that the task-id->percentage map is multiplied by the minutes correctly "
    (let [task-id->minutes-from-pcts #'timecop.businesstime/task-id->minutes-from-pcts]
      (is (= {123 27 234 22 345 51}
             (task-id->minutes-from-pcts {123 0.272 234 0.222 345 0.506} 100))))))

(deftest move-to-time-test
  (testing "Test an event is correctly moved"
    (let [move-to-time #'timecop.businesstime/move-to-time]
      (testing "forward in time"
        (let [new-start (LocalDateTime/of 2018 6 11 23 1)
              new-event (assoc mock-canonical-event-over-two-nights-whole
                               :start-time new-start
                               :end-time (LocalDateTime/of 2018 6 13 9 1))]
          (is (= new-event
                 (move-to-time mock-canonical-event-over-two-nights-whole new-start))))
        (testing "backwards in time"
          (let [new-start (LocalDateTime/of 2016 4 8 21 59)
                new-event (assoc mock-canonical-event-over-two-nights-whole
                                 :start-time new-start
                                 :end-time (LocalDateTime/of 2016 4 10 7 59))]
            (is (= new-event
                   (move-to-time mock-canonical-event-over-two-nights-whole new-start)))))))))

(defn mock-event
  [start-time duration & {:keys [description source source-id task-type] :or
                          {description TC_DESCRIPTION
                           source TC_SOURCE
                           source-id TC_SOURCE_ID
                           task-type :mock-task}}]
  (let [end-time (.plusMinutes start-time duration)]
    (->CanonicalEvent start-time end-time description source source-id task-type)))

(deftest slam-to-earliest-test
  (testing "Dig through the ditches and burn through the witches"
    (testing "and slam through the back of my dragula"
      (let [workday-start #'timecop.businesstime/workday-start
            duration 30
            start-time (LocalDateTime/of 2017 5 10 22 0)
            event (mock-event start-time duration)
            moved-start-time (workday-start event)
            moved-end-time (.plusMinutes moved-start-time duration)
            moved-event-correct (assoc event :start-time moved-start-time :end-time moved-end-time)
            moved-event (first (slam-to-earliest [] event))]
        (is (= moved-event-correct
               moved-event))))
    (testing "event before start of workday unchanged"
      (let [duration 30
            start-time (LocalDateTime/of 2017 5 10 7 0)
            event (mock-event start-time duration)]
        (is (= [event]
               (slam-to-earliest [] event)))))
    (testing "event place at end of list"
      (let [duration 30
            duration-2 60
            start-time (LocalDateTime/of 2017 5 10 10 0)
            start-time-2  (LocalDateTime/of 2017 5 10 12 0)
            event (mock-event start-time duration)
            event-2 (mock-event start-time-2 duration-2)
            start-time-3 (LocalDateTime/of 2017 5 10 22 0)
            event-3 (mock-event start-time-3 duration)
            moved-start-time (.plusMinutes start-time-2 duration-2)
            moved-end-time (.plusMinutes moved-start-time duration)
            moved-event-correct (assoc event-3 :start-time moved-start-time :end-time moved-end-time)
            events [event event-2]]
        (is (= [moved-event-correct event event-2]
               (slam-to-earliest events event)))))))

(deftest add-minutes-to-day-test
  (testing "Events are added from a map of task to minutes"
    (let [event-1 (mock-event (LocalDateTime/of 2017 5 10 10 0) 30)
          event-2 (mock-event (LocalDateTime/of 2017 5 10 13 30) 60)
          events [event-2 event-1]
          [t1 t2 t3] [60 120 300]
          task-id->minutes {"task-1" t1 "task-2" t2 "task-3" t3}
          created-event-1 (mock-event (LocalDateTime/of 2017 5 10 14 30)
                                      t1
                                      :task-type :task-1)
          created-event-2 (mock-event (LocalDateTime/of 2017 5 10 15 30)
                                      t2
                                      :task-type :task-2)
          created-event-3 (mock-event (LocalDateTime/of 2017 5 10 17 30)
                                      t3
                                      :task-type :task-3)]
      (is (= [created-event-3 created-event-2 created-event-1 event-2 event-1]
             (add-minutes-to-day task-id->minutes events))))))

(deftest add-pcts-to-day-test
  (testing "Test that the correct amount of time is filled according to the provided percentages"
    (testing "with reasonable values"
      (let [event-1 (mock-event (LocalDateTime/of 2017 5 10 10 0) 30)
            event-2 (mock-event (LocalDateTime/of 2017 5 10 13 30) 60)
            events [event-2 event-1]
            minutes-worked (* 9.5 60)
            task-id->pcts {"task-1" 0.125 "task-2" 0.25 "task-3" 0.625}
            created-event-1 (mock-event (LocalDateTime/of 2017 5 10 14 30)
                                        60.0
                                        :task-type :task-1)
            created-event-2 (mock-event (LocalDateTime/of 2017 5 10 15 30)
                                        120.0
                                        :task-type :task-2)
            created-event-3 (mock-event (LocalDateTime/of 2017 5 10 17 30)
                                        300.0
                                        :task-type :task-3)]
        (is (= [created-event-3 created-event-2 created-event-1 event-2 event-1]
               (add-pcts-to-day task-id->pcts minutes-worked events)))))))
