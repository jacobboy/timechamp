(ns timecop.schema
  (:require [schema.core :as s])
  (:import java.time.LocalDateTime))

;; Use schema for the hell of it, because I've never used it before
(s/defrecord CanonicalEvent
    [start-time :- LocalDateTime ; LocalDateTimes are immutable
     end-time :- LocalDateTime
     description :- s/Str
     source :- s/Keyword
     source-id :- s/Str
     task-type :- s/Keyword])

(defn canonical-event [{:keys [start-time end-time description
                               source source-id task-type]}]
  (->CanonicalEvent start-time end-time description source source-id task-type))

;; (defmulti canonical-event #(:source (meta %)))

;; (defmethod canonical-event :gc [gc-event]
;;   (let [event (select-keys gc-event ["start" "end" "summary"])]
;;     event))

;; (defmethod canonical-event :tc [tc-event]
;;   (tc-event))
