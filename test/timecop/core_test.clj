(ns timecop.core-test
  (:require [clojure.test :refer :all]
            [timecop.core :refer :all])
  (:import com.google.api.client.util.DateTime
           [com.google.api.services.calendar.model Event EventDateTime]))

(def example-gc-event
  (-> (Event.)
      (.setStart (.setDateTime (EventDateTime.) (DateTime. "2017-05-10T10:15:00.000-04:00")))
      (.setEnd (.setDateTime (EventDateTime.) (DateTime. "2017-05-10T10:30:00.000-04:00")))
      (.setSummary "Review Preparation")))

(def example-tc-event
  {
   :date "2017-05-10"
   :duration "900"
   :start_time "10:15:00"
   :end_time "10:30:00"
   :note "Review Preparation"
   :task_id "9238867"
   })

(deftest test-gc-date-to-tc-date
  (testing "A Google DateTime gets transformed into yyyy-mm-dd"
    (is (= (gc-date-to-tc-date (DateTime. "2017-05-10T10:15:00.000-04:00"))
           "2017-05-10"))))

(deftest test-gc-date-to-tc-time
  (testing "A Google DateTime gets transformed into HH:MM:SS"
    (is (= (gc-date-to-tc-time (DateTime. "2017-05-10T10:15:00.000-04:00"))
           "10:15:00"))))

(deftest test-end-of-day
  (testing "Returns 23:59:59:999 on the same day and TZ as the provided DateTime"
    (is (= "2017-05-10T23:59:59.999-04:00"
           (str (end-of-day (DateTime. "2017-05-10T10:15:00.000-04:00")))))))

(deftest test-beginning-of-next-day
  (testing "Returns 00:00:00:000 on the next day and same TZ as the provided DateTime"
    (is (= "2017-05-11T00:00:00.000-04:00"
           (str (end-of-day (DateTime. "2017-05-10T10:15:00.000-04:00")))))))

(deftest test-gc-event-to-tc-events
  (testing "A simple Google event gets transformed properly"
    (is (= [example-tc-event] (gc-event-to-tc-events example-gc-event)))))
