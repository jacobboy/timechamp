(ns timecop.core-test
  (:require [clojure.test :refer :all]
            [timecop.core :refer :all])
  (:import com.google.api.client.util.DateTime
           [com.google.api.services.calendar.model Event EventDateTime]))

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
    :duration "900"
    :note "summary: Review Preparation\nexternal-id: Simple ID\n",
    :task_id "9238867"
    }])

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
    :task_id "9238867"
    }])

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

(deftest test-gc-date-to-tc-date
  (testing "A Google DateTime gets transformed into yyyy-mm-dd"
    (is (= (gc-date-to-tc-date (DateTime. "2017-05-10T10:15:00.000-04:00"))
           "2017-05-10"))))

(deftest test-gc-date-to-tc-time
  (testing "A Google DateTime gets transformed into HH:MM:SS"
    (is (= (gc-date-to-tc-time (DateTime. "2017-05-10T10:15:00.000-04:00"))
           "10:15:00"))))

(deftest test-beginning-of-day
  (testing "Returns 00:00:00.000 on the same day and TZ as the provided DateTime"
    (is (= "2017-05-10T00:00:00.000-04:00"
           (str (beginning-of-day (DateTime. "2017-05-10T10:15:00.000-04:00")))))))

(deftest test-end-of-day
  (testing "Returns 23:59:59:999 on the same day and TZ as the provided DateTime"
    (is (= "2017-05-10T23:59:59.999-04:00"
           (str (end-of-day (DateTime. "2017-05-10T10:15:00.000-04:00")))))))

(deftest test-plus-one-day
  (testing "Returns the same time on the next day and same TZ as the provided DateTime"
    (is (= "2017-05-01T10:15:00.000-04:00"
           (str (plus-one-day (DateTime. "2017-04-30T10:15:00.000-04:00")))))))

(deftest test-beginning-of-next-day
  (testing "Returns 00:00:00:000 on the next day and same TZ as the provided DateTime"
    (is (= "2017-05-11T00:00:00.000-04:00"
           (str (beginning-of-next-day (DateTime. "2017-05-10T10:15:00.000-04:00")))))))

(deftest test-gc-event-to-tc-events-simple
  (testing "A simple Google event is transformed properly"
    (is (= mock-tc-events-simple (gc-event-to-tc-events mock-gc-event-simple)))))

(deftest test-gc-event-to-tc-events-overnight
  (testing "A Google event across midnight is transformed properly"
    (is (= mock-tc-events-overnight (gc-event-to-tc-events mock-gc-event-overnight)))))

(deftest test-gc-event-to-tc-events-over-two-nights
  (testing "A Google event across multiple midnights is transformed properly"
    (is (= mock-tc-events-over-two-nights (gc-event-to-tc-events mock-gc-event-over-two-nights)))))
