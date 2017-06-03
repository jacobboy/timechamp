(ns timecop.businesstime
  (:require [schema.core :as s]
            [timecop.schema :refer [canonical-event]])
  (:import [java.time LocalDate LocalDateTime LocalTime]
           java.time.temporal.ChronoUnit
           timecop.schema.CanonicalEvent))

(def ^:const TC_DESCRIPTION  "Created by TimeCop")
(def ^:const TC_SOURCE :timecop)
(def ^:const TC_SOURCE_ID "TimeCop ID")

(s/defn first-second-of-date [date :- LocalDate]
  (.atStartOfDay date))

(s/defn last-second-of-date [date :- LocalDate]
  (.atTime date 23 59 59))

(s/defn beginning-of-next-day [datetime :- LocalDateTime]
  (-> datetime (.toLocalDate) (.plusDays 1) first-second-of-date))

(s/defn end-of-day [datetime :- LocalDateTime]
  (-> datetime (.toLocalDate) last-second-of-date))

(s/defn split-event-at-midnight :- [CanonicalEvent]
  "Break an event that spans multiple days into multiple events that
  do not cross midnight, for compatibility with TimeCamp.'"
  [{:keys [start-time end-time description
           source source-id task-type] :as event} :- CanonicalEvent]
  (letfn [(date [datetime] (.toLocalDate datetime))]
    (if (= (date (:start-time event)) (date (:end-time event)))
      [event]
      (let [event-first (assoc event :end-time (end-of-day start-time))
            event-rest (assoc event :start-time (beginning-of-next-day start-time))]
        (cons event-first (split-event-at-midnight event-rest))))))

(defn event-duration-minutes [event]
  ;; Google Calendar has minutes resolutions
  (.until (:start-time event) (:end-time event) ChronoUnit/MINUTES))

(defn task-id->minutes
  "Multiply the values of task-id->pcts by the minutes argument,
  creating a map from task id to minutes according to the percentages
  defined in task-id->pcts. Resulting values are rounded to the nearest
  minute."
  [task-id->pcts minutes]
  (reduce-kv #(assoc %1 %2 (Math/round (* %3 minutes))) {} task-id->pcts))

(defn move-to-time
  "Move the event to the specified time"
  [event start-time]
  (let [duration (event-duration-minutes event)
        ;; this new time api is pretty great
        end-time (.plusMinutes start-time duration)]
    (assoc event :start-time start-time :end-time end-time)))

;; TODO bad fn name
(defn workday-start [event]
  (LocalDateTime/of (.toLocalDate (:start-time event)) (LocalTime/of 9 0 0)))

(defn move-to-start [event]
  (let [start-of-workday (workday-start event)]
    (if (.isBefore start-of-workday (:start-time event))
      (move-to-time event start-of-workday)
      event)))

(defn later-event [event-a event-b]
  (if (<= 0 (compare (:end-time event-a) (:end-time event-b)))
    event-a
    event-b))

(s/defn slam-to-earliest :- [CanonicalEvent]
  "Move the event provided to immediately follow the latest event in the list of events.
  If the provided list is empty, move the event to the beginning of the workday unless
  the event is earlier than the workday start. If the event is earlier, does not move it.
  Returns the list of events with the moved event added."
  [events :- [CanonicalEvent] event :- CanonicalEvent]
  (if (empty? events)
    (list (move-to-start event))
    (let [;; doesn't assume events is ordered, so technically less performant
          latest-event (reduce later-event events)
          start-time (:end-time latest-event)] ; TimeCamp events do start and end simultaneously
      (cons (move-to-time event start-time) events))))

(defn event-from-task-minutes
  [start-time minutes task-id]
  (canonical-event {:start-time start-time
                    :end-time (.plusMinutes start-time minutes)
                    :description TC_DESCRIPTION
                    :source TC_SOURCE
                    :source-id TC_SOURCE_ID
                    :task-type (keyword task-id)}))

(defn add-events-at-end [[events latest-event task-minutes]]
  (if (empty? task-minutes)
    events
    (let [start-time (:end-time latest-event)
          [task-id minutes] (first task-minutes)
          event (event-from-task-minutes start-time minutes task-id)
          new-events (cons event events)]
      (add-events-at-end [new-events event (rest task-minutes)]))))

(defn fill-day-by-percents [events task-id->pcts hours]
  (cond
    (< 1.0 (reduce + (vals task-id->pcts)))
    (throw (Exception. (format "Invalid task percentages: %s" task-id->pcts)))

    (> hours 15) ; TODO breaks when combined with setting the earliest event to 9am
    (throw (Exception. (format "Too many hours worked: %d " hours)))

    :else
    (let [minutes-worked (* hours 60)
          total-duration (reduce + (map event-duration-minutes events))
          minutes-remaining (- minutes-worked total-duration)
          sorted-events (sort-by :start-time events)]
      (if-not (pos? minutes-remaining)
        sorted-events
        (let [task-minutes (task-id->minutes task-id->pcts minutes-remaining)
              squeezed-events (reduce slam-to-earliest [] sorted-events)
              latest-event (reduce later-event squeezed-events)]
          (add-events-at-end [squeezed-events latest-event task-minutes]))))))
