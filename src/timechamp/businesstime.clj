(ns timechamp.businesstime
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [schema.core :as s]
            [timechamp.schema :refer [canonical-event]])
  (:import [java.time LocalDate LocalDateTime LocalTime]
           java.time.temporal.ChronoUnit
           timechamp.schema.CanonicalEvent))

(def ^:const TC_DESCRIPTION  "Created by TimeChamp")
(def ^:const TC_SOURCE :timechamp)
(def ^:const TC_SOURCE_ID "TimeChamp ID")

(def ^:const HOURS_MINS_RE
  #"(?x)                    # allow embedded whitespace and comments
    ^                       # match start
    (?=.)                   # positive lookahead for . to avoid matching empty string
    (?:(\d*(?:\.\d*)?)h)?   # int or float, followed by 'h', capture the number
    (?:(\d*(?:\.\d*)?)m)?   # same, followed by 'm', capture number
    $                       # match end")
(def ^:const PCT_RE #"^(\d*(?:\.\d*)?)%$")

(defn round
  "Round number to the nearest int"
  [num]
  (if (float? num) (Math/round num) num))

(s/defn first-second-of-date :- LocalDateTime [date :- LocalDate]
  (.atStartOfDay date))

(s/defn last-second-of-date :- LocalDateTime [date :- LocalDate]
  (.atTime date 23 59 59))

(s/defn ^:private beginning-of-next-day [datetime :- LocalDateTime]
  (-> datetime (.toLocalDate) (.plusDays 1) first-second-of-date))

(s/defn ^:private end-of-day [datetime :- LocalDateTime]
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
        (cons event-first (
split-event-at-midnight event-rest))))))

(defn ^:private event-duration-minutes
  "Length in minutes of the provided event.  Minutes also happens to be
  the highest resolution of Google Calendar."
  [event]
  (.until (:start-time event) (:end-time event) ChronoUnit/MINUTES))

(defn ^:private pcts-to-minutes
  "Return the number of minutes corresponding to the provided percent of total
  minutes, rounded to the nearest minute. If passed only a single argument,
  returns this function with that argument partially applied as the total
  minutes."
  ([total-minutes]
   (partial pcts-to-minutes total-minutes))
  ([total-minutes pct]
   (round (* pct total-minutes))))

(s/defn hours-str-to-minutes :- s/Num
  "Parse the hours/minutes input string into the number of minutes it
  represents.  Returns 0 if the argument does not match the `XhYm` hours/minutes
  pattern."
  [hours-mins :- s/Str]
  (let [[_ hours-str mins-str] (re-find HOURS_MINS_RE hours-mins)
        hours (if (nil? hours-str) 0 (read-string hours-str))
        mins (if (nil? mins-str) 0 (read-string mins-str))]
    (+ (pcts-to-minutes 60 hours) (round mins))))

(s/defn pct-strs-to-num :- s/Num
  "Parse the percents input string into a number.  Returns 0 if the argument
  does not match the `X%` percent pattern."
  [pct-str :- s/Str]
  (let [[_ pct :as match] (re-find PCT_RE pct-str)]
    (if (some? match)
      (/ (Double/parseDouble pct) 100)
      0.0)))

(defn ^:private task-id->minutes-from-pcts
  "Multiply the values of task-id->pcts by the minutes argument,
  creating a map from task id to minutes according to the percentages
  defined in task-id->pcts. Resulting values are rounded to the nearest
  minute."
  [task-id->pcts minutes]
  (reduce-kv #(assoc %1 %2 (round (* %3 minutes))) {} task-id->pcts))

(defn ^:private move-to-time
  "Move the event to the specified time"
  [event start-time]
  (let [duration (event-duration-minutes event)
        ;; this new time api is pretty great
        end-time (.plusMinutes start-time duration)]
    (assoc event :start-time start-time :end-time end-time)))

;; TODO bad fn name
(defn ^:private workday-start
  "Return a LocalDateTime representing the start of the workday on the day of
  the event"
  [event]
  (LocalDateTime/of (.toLocalDate (:start-time event)) (LocalTime/of 9 0 0)))

(defn ^:private move-to-start
  "Move an event backwards to the start of the workday on the same day,
  but return event unchanged if the event is prior to the workday start."
  [event]
  (let [start-of-workday (workday-start event)]
    (if (.isBefore start-of-workday (:start-time event))
      (move-to-time event start-of-workday)
      event)))

(defn ^:private later-event
  "Return the event which *ends* latest."
  [event-a event-b]
  (if (<= 0 (compare (:end-time event-a) (:end-time event-b)))
    event-a
    event-b))

(s/defn slam-to-earliest :- [CanonicalEvent]
  "Move the event provided to immediately follow the latest event in the list of
  events.  If the provided list is empty, move the event to the beginning of the
  workday unless the event is earlier than the workday start. If the event is
  earlier, does not move it.
  Returns the list of events with the moved event added."
  [events :- [CanonicalEvent] event :- CanonicalEvent]
  (if (empty? events)
    (list (move-to-start event))
    (let [;; doesn't assume events is ordered, so technically less performant
          latest-event (reduce later-event events)
          ;; TimeCamp events start and end simultaneously
          start-time (:end-time latest-event)]
      (cons (move-to-time event start-time) events))))

(defn ^:private event-from-task-minutes
  "New CanonicalEvent of length minutes, task task-id, and beginning at
  start-time, with default source and source-id."
  [start-time minutes task-id]
  (canonical-event {:start-time start-time
                    :end-time (.plusMinutes start-time minutes)
                    :description TC_DESCRIPTION
                    :source TC_SOURCE
                    :source-id TC_SOURCE_ID
                    :task-type (keyword task-id)}))

(s/defn add-minutes-to-day :- [CanonicalEvent]
  "Create back-to-back events from the provided task-id->minutes map, beginning
  immediately after the latest event provided, and return all events."
  ([task-id->minutes :- {s/Str s/Num}
    events :- [CanonicalEvent]]
   (let [latest-event (reduce later-event events)]
     (add-minutes-to-day task-id->minutes events latest-event)))

  ([task-id->minutes events latest-event]
   (if (empty? task-id->minutes)
     events
     (let [start-time (:end-time latest-event)
           [task-id minutes] (first task-id->minutes)
           event (event-from-task-minutes start-time minutes task-id)
           new-events (cons event events)]
       (add-minutes-to-day (rest task-id->minutes) new-events event)))))

(s/defn add-pcts-to-day :- [CanonicalEvent]
  "Create back-to-back events with events taking the percentage of
  minutes-worked specified in task-id->pcts map, beginning immediately after the
  latest event provided. Return all events."
  [task-id->pcts :- {s/Str s/Num}
   minutes-worked :- s/Num
   events :- [CanonicalEvent]]
  (let [total-duration (reduce + (map event-duration-minutes events))
        minutes-remaining (- minutes-worked total-duration)]
    (if-not (pos? minutes-remaining)
      events
      (let [task-id->minutes
              (fmap (pcts-to-minutes minutes-remaining) task-id->pcts)]
        (add-minutes-to-day task-id->minutes events)))))
