(ns timecop.core
  (:import com.google.api.client.util.DateTime
           com.google.api.services.calendar.model.Event
           java.lang.System))

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

(defn end-of-day [^DateTime datetime]
  (let [datetime-str (str datetime)
        date-str (subs datetime-str 0 11)
        tz-str (subs datetime-str 23)]
    (DateTime. (str date-str "23:59:59.999" tz-str))))

(defn gc-event-to-tc-events [^Event gc-event]
  (let [start-date (gc-date-to-tc-date start)
        end-date (gc-date-to-tc-date end)
        start (.. gc-event getStart getDateTime)
        end (.. gc-event getEnd getDateTime)
        summary (. gc-event getSummary)
        duration (gc-duration start end)]
    (if (= start-date end-date)
      [{:date start-date
       :start_time (gc-date-to-tc-time start)
       :end_time (gc-date-to-tc-time end)
       :duration (str duration)
       :note summary
       :task_id "9238867"}]
      (let [gc-event-first (.setEnd (.clone gc-event) (end-of-day (.getStart gc-event)))
            gc-event-rest (.setStart (.clone gc-event) (beginning-of-next-day (.getStart gc-event)))]
        (flatten [(gc-event-to-tc-events gc-event-first)
                  (gc-event-to-tc-events gc-event-rest)])))))

;; (def g (let [calList "primary"
;;              maxResults (int 1)
;;              timeMin (DateTime. "2017-05-10T08:00:00.000-04:00")
;;              events (gc/get-events calList maxResults timeMin GC_SECRETS DATA_STORE_DIR)]
;;          (map gc-event-to-tc-events events)))
;; => #'timecop.core/g


;; (def t (tc/get-entries TC_API_TOKEN "103626" "2017-04-04" "2017-04-05"))
