(ns timecop.core
  (:require [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [timecop.gc-util :as gc]
            [timecop.tc-util :as tc])
  (:import com.google.api.client.util.DateTime
           [com.google.api.services.calendar.model Event EventDateTime]
           java.lang.System))

;; TODO using environment because I don't know what else to use right now
(def TC_API_TOKEN (System/getenv "TC_API_TOKEN"))
(def GC_SECRETS (System/getenv "TIMECOP_SECRETS_LOC"))
(def DATA_STORE_DIR (System/getenv "TIMECOP_CREDENTIALS_LOC"))

;; (defmulti canonical-event #(:source (meta %)))

;; (defmethod canonical-event :gc [gc-event]
;;   (let [event (select-keys gc-event ["start" "end" "summary"])]
;;     event))

;; (defmethod canonical-event :tc [tc-event]
;;   (tc-event))

(defn gc-date-to-tc-date [^DateTime gc-date]
  (subs (str gc-date) 0 10))

(defn gc-date-to-tc-time [^DateTime gc-date]
  (subs (str gc-date) 11 19))

(defn gc-duration [^DateTime start ^DateTime end]
  (let [start-millis (.getValue start)
        end-millis (.getValue end)]
    (int (/ (- end-millis start-millis) 1000))))

(defn beginning-of-day [^DateTime datetime]
  (let [datetime-str (str datetime)
        date-str (subs datetime-str 0 11)
        tz-str (subs datetime-str 23)]
    (DateTime. (str date-str "00:00:00.000" tz-str))))

(defn end-of-day [^DateTime datetime]
  (let [datetime-str (str datetime)
        date-str (subs datetime-str 0 11)
        tz-str (subs datetime-str 23)]
    (DateTime. (str date-str "23:59:59.999" tz-str))))

(defn plus-one-day [^DateTime datetime]
  (let [millis-per-day (* 24 60 60 1000)]
    (DateTime. (+ (.getValue datetime) millis-per-day)
               (.getTimeZoneShift datetime))))

(def beginning-of-next-day (comp beginning-of-day plus-one-day))

(defn gc-event-to-tc-events [^Event gc-event]
  (let [start (.. gc-event getStart getDateTime)
        end (.. gc-event getEnd getDateTime)
        start-date (gc-date-to-tc-date start)
        end-date (gc-date-to-tc-date end)
        summary (. gc-event getSummary)
        external-id (. gc-event getId)
        duration (gc-duration start end)]
    (if (= start-date end-date)
      [{:date start-date
        :start_time (gc-date-to-tc-time start)
        :end_time (gc-date-to-tc-time end)
        :duration (str duration)
        ;; timecamp couldn't deal with the characters in json, use yaml to avoid quotes
        :note (yaml/generate-string {:summary summary :external-id external-id}
                                    :dumper-options {:flow-style :block})
        :task_id tc/MEETINGS_TASK_ID}]
      (let [gc-event-first (.setEnd (.clone gc-event)
                                    (.setDateTime (EventDateTime.) (end-of-day start)))
            gc-event-rest (.setStart (.clone gc-event)
                                     (.setDateTime (EventDateTime.) (beginning-of-next-day start)))]
        (flatten [(gc-event-to-tc-events gc-event-first)
                  (gc-event-to-tc-events gc-event-rest)])))))

(defn post-tc [tc-event]
  (let [{:keys [status body] :as response} (tc/post-tc TC_API_TOKEN tc-event)]
    {:ok (>= 300 status)
     :status status
     :body (json/read-str body)
     :tc-event tc-event}))

(defn -main []
  (let [cal-list "primary"
        max-results 1
        time-min (DateTime. "2017-05-10T08:00:00.000-04:00")
        time-max (DateTime. "2017-05-11T08:00:00.000-04:00")
        gc-events (gc/get-events cal-list max-results time-min time-max GC_SECRETS DATA_STORE_DIR)
        tc-events (mapcat gc-event-to-tc-events gc-events)
        {successes true
         failures false} (group-by :ok (map post-tc tc-events))]
    (when (some? failures) (json/pprint failures))
    (print (format "%d successes, %d failures" (count successes) (count failures)))))
