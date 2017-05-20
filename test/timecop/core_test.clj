(ns timecop.core-test
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [timecop.core :refer :all]
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

(deftest test-beginning-of-next-day
  (testing "Returns 00:00:00:000 on the next day"
    (is (= (LocalDateTime/of 2015 9 1 0 0)
           (beginning-of-next-day (LocalDateTime/of 2015 8 31 4 15))))))

(deftest test-end-of-day
  (testing "Returns 23:59:59 on the same day"
    (is (= (LocalDateTime/of 2019 12 31 23 59 59)
           (end-of-day (LocalDateTime/of 2019 12 31 0 1))))))

(deftest simple-event-unsplit
  (testing "A simple canonical event is unchanged by split-event-at-midnight"
    (is (= [mock-canonical-event-simple]
           (split-event-at-midnight mock-canonical-event-simple)))))

(deftest overnight-event-split
  (testing "An overnight event is split into two by split-event-at-midnight"
    (is (= mock-canonical-events-overnight-split
           (split-event-at-midnight mock-canonical-event-overnight-whole)))))

(deftest over-2-night-event-split
  (testing "A canonical event across multiple midnights is split into 3 by split-event-at-midnight"
    (is (= mock-canonical-events-over-two-nights-split
           (split-event-at-midnight mock-canonical-event-over-two-nights-whole)))))

(deftest test-date-validation
  (testing "Input dates are properly validated"
    (testing "A good date passes"
      (is (= true
             (yyyy-MM-dd? "2017-05-09"))))
    (testing "A bad date fails"
      (is (= false
             (yyyy-MM-dd? "20170509"))))))
