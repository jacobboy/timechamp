(ns timecop.date-helpers
  (:import com.google.api.client.util.DateTime))

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

;; TODO can this get screwed up? events at midnight on days with leap seconds?
(def beginning-of-next-day (comp beginning-of-day plus-one-day))
