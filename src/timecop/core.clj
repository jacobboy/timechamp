(ns timecop.core
  (:gen-class)
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.data :refer :all]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts make-summary-part]]
            [schema.core :as s]
            [timecop.businesstime :refer :all]
            [timecop.gc-util :as gc]
            [timecop.tc-util :as tc]
            [clojure.pprint :as pp])
  (:import java.lang.System
           [java.time DayOfWeek LocalDate]
           timecop.schema.CanonicalEvent))

(def ^:const required-opts #{})  ; no required options anymore
(def ^:const INPUT_RE (re-pattern (format "%s\n|%s" HOURS_MINS_RE PCT_RE)))
(def ^:const WEEKEND_DAYS #{DayOfWeek/SATURDAY DayOfWeek/SUNDAY})

(defn indent [line indent-level]
  (pp/cl-format nil "~vA~A" indent-level "" line))

(defn fit-to-line-length
  ([indent-level line-length line]
   (let [len (- line-length indent-level)
         line (str/replace line #"\s+" " ")]
     (if (> (count line) len)
       (let [last-space-in-line (str/last-index-of line " " len)
             first-space (str/index-of line "")
             space-to-split (or last-space-in-line first-space)]
         (if (some? space-to-split)
           (let [first-line (subs line 0 space-to-split)
                 rest-of-line (subs line (+ space-to-split 1))]
             (str (indent first-line indent-level)
                  \newline
                  (fit-to-line-length indent-level line-length rest-of-line)))
           [(indent line indent-level)]))
       (indent line indent-level))))
  ([indent-level line]
   (fit-to-line-length indent-level 80 line)))

(defn format-opt-line [part]
  (let [[name default description] part
        fmt (if (empty? default)
              "  ~A\n~*~A"
              "  ~A\n      \u001B[3mDefault: ~A\u001B[0m\n~A")]
    (pp/cl-format nil fmt
                  name
                  default
                  (fit-to-line-length 6 description))))

(defn summary-fn
  [specs]
  (if (seq specs)
    (let [show-defaults? (some #(and (:required %) (contains? % :default)) specs)
          parts (map #(map str/triml %)
                     (map (partial make-summary-part show-defaults?) specs))
          lines (map format-opt-line parts)]
      (str/join "\n\n" lines))
    ""))

(def option-specs
  [["-s" "--start-date START_DATE" "Start date (inclusive) in yyyy-MM-dd format"
    ;; going through LocalDate ended up much simpler than trying to use LocalDateTime directly
    :parse-fn #(LocalDate/parse %) :default (LocalDate/now) :default-desc "Today"]

   ["-e" "--end-date END_DATE" "End date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(LocalDate/parse %) :default (LocalDate/now) :default-desc "Today"]

   [nil "--hours-worked HOURS"
    (str "Hours worked per day. Must be less than 15, as events are moved to begin at 9am.")
    :parse-fn #(. Double parseDouble %1)
    :validate-fn #(< 0 % 15) ; >15 not compatible with 9:00 start of day
    :default 8]

   ["-c" "--client-secrets-file CLIENT_SECRETS_FILE"
    "Path to Google client secrets JSON file. See README for more information."
    :default (System/getenv "TIMECOP_SECRETS_FILE")
    :default-desc "$TIMECOP_SECRETS_FILE"]

   ["-d" "--data-store-dir DATA_STORE_DIR"
    "Path to the folder where the Google client will save its auth information
     between uses, allowing you to not need to reauthorize your Google credentials
     each use. This can be `/tmp` if desired, though you'll have to re-authorize as
     it is cleaned up. "
    :default (System/getenv "TIMECOP_CREDENTIALS_DIR")
    :default-desc "$TIMECOP_CREDENTIALS_DIR"]

   ["-t" "--tc-api-token TC_API_TOKEN"
    "TimeCamp API token. See README for more information."
    :default (System/getenv "TC_API_TOKEN") :default-desc "$TC_API_TOKEN"]

   ["-i" "--calendar-id CALENDAR_ID"
    (str "ID of the Google calendar to read events from. Unless you've created "
         "multiple calendars on the account and know which you want, the default "
         "is what you want.")
    :default "primary"]

   ["-w" "--include-weekends" "Create events for weekends."
    :id :include-weekends?]

   ["-h" "--help"]])

(def pos-args-summary-lines
  ["Optional.  Ex: 1234 1h 2345 1h30m 3456 80% 4567 20%"
   ""
   "A TASK_TIME_PAIR is a space-separated pair of a (numeric) TimeCamp
    task id and either an absolute length time or a percentage of free
    time. Timecop will first create events from any tasks with absolute
    durations provided, then, if total event duration is less than hours
    worked, will portion the remaining time according to any percentages
    supplied."
   "Absolute times are specifed as hours and/or minutes in the form
    `XhYm`, e.g. `1h30m`, `1.5h`, `90m`. Percentages are specified as
    `X%`, e.g. `25%`.  Thus the list of TASK_TIME_PAIR... might look like"
    "`1234 1h 2345 30m 3456 80% 4567 20%`."
   "Hours, minutes, and percents may be floats. Percents need not sum to 1; it
    is valid to specify tasks take up only a fraction of the unallocated
    time. Percentages greater than 1 are also respected for some reason."])

(def pos-args-summary
  (str/join
   \newline
   [(indent "TASK_TIME_PAIR..." 2)
    (str/join \newline (map #(fit-to-line-length 6 %) pos-args-summary-lines))]))

(defn usage [options-summary]
  (->> [
        ;; (fit-to-line-length
        ;;  0 80
        ;;  "Blindly shove Google calendar events into TimeCamp, optionally filling in
        ;;   remaining time. Currently keeps only durations, throwing away specific
        ;;   start and end times by moving events to the beginning of the workday.")
        ;; ""
        (str/join
         \newline
         ["Usage: "
          "  timecop [-s START_DATE] [-e END_DATE] [--hours-worked HOURS]"
          "          [-c CLIENT_SECRETS_FILE] [-d DATA_STORE_DIR] [-t TC_API_TOKEN]"
          "          [-i CALENDAR_ID] [-w] [TASK_TIME_PAIR...]"])
        ""
        "Positional arguments:"
        pos-args-summary
        ""
        "Named arguments:"
        options-summary
        ""
        "Please refer to the README at for more information"]
       (str/join \newline)))

(defn missing-required
  "Returns any required options missing from the supplied opts"
  [opts]
  (let [[only-opts only-req both] (diff (set (keys opts)) required-opts)]
    only-req))

(defn args-invalid? [args]
  (not
   (and
    (some? args)
    (coll? args) ; is this a stupid check?
    ;; (pos? (count args))  ;; can be empty
    (even? (count args))  ;; must be key value pairs (allows empty)
    ;; each key should be an int, TimeCamp IDs are ints
    (every? some? (map #(re-find #"^\d*$" %) (take-nth 2 args)))
    ;; each value should be an hours/minutes combo or a percent
    (every? some? (map #(re-find INPUT_RE %) (take-nth 2 (rest args)))))))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [{:keys [arguments options errors summary]} (parse-opts cli-args option-specs
                                                               :summary-fn summary-fn)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      ;; TODO provide useful messages
      (seq (missing-required options)) ; use seq instead of (not (empty? ,,,))?
      {:exit-message (error-msg ["Missing required options"])}

      (args-invalid? arguments)
      {:exit-message (error-msg ["invalid args"])}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; require start-date and end-date parameters
      (not-every? (partial contains? options) [:start-date :end-date])
      {:exit-message (error-msg ["Must provide both start and end date as yyyy-MM-dd values"])}

      (.isAfter (:start-date options) (:end-date options))
      {:exit-message (error-msg ["End date must be after start date"])}

      :else
      {:args (assoc options :arguments arguments)})))

(defn exit [status msg] (println msg) (System/exit status))

;; ^   args
;; \/  logic

(defn- pct-pair? [[task-id time-str]]
  (some? (re-find PCT_RE time-str)))

(defn task-id->time-from-arguments
  [arguments]
  (let [args-map (apply hash-map arguments)
        grouped-args (group-by pct-pair? args-map)
        grouped-args-maps (fmap #(into {} %) grouped-args)
        {pre-task-id->minutes false
         pre-task-id->pcts true} grouped-args-maps]
    {:task-id->minutes (fmap hours-to-minutes pre-task-id->minutes)
     :task-id->pcts (fmap pct-strs-to-num pre-task-id->pcts)}))

(s/defn event-day :- LocalDate [event :- CanonicalEvent]
  (.toLocalDate (:start-time event)))

(s/defn days-between-start-end :- [LocalDate]
  "Return a sequence of dates between start-date and end-date, inclusive"
  [start-date :- LocalDate end-date :- LocalDate]
  ;; there's literally got to tbe a better way, but I just
  ;; cannot be arsed right now
  (if (= start-date end-date)
    (list start-date)
    (let [tomorrow (.plusDays start-date 1)]
      (cons start-date (days-between-start-end tomorrow end-date)))))

(defn weekend-filter [include-weekends?]
  (if include-weekends?
    (constantly true)
    (s/fn [date :- LocalDate]
      (not (contains? #{DayOfWeek/SATURDAY DayOfWeek/SUNDAY}
                      (.getDayOfWeek date))))))

(defn days-between-inclusive [start-date end-date include-weekends?]
  (filter (weekend-filter include-weekends?)
          (days-between-start-end start-date end-date)))

(defn- create-day-event-maps
  [events days-covered]
  (let [days->default-events (reduce #(assoc %1 %2 []) {} days-covered)]
    (as-> events m
      (mapcat split-event-at-midnight m)
      (group-by event-day m)
      ;; at least day->[] for every day in consideration
      (merge days->default-events m)
      ;; keep only those in days-covered
      (select-keys m days-covered))))

(defn- add-user-times-to-day
  "TODO doesn't use localdate"
  [localdate events task-id->minutes task-id->pcts minutes-worked]
  (->> events
       (sort-by :start-time)
       (reduce slam-to-earliest [])
       (add-minutes-to-day task-id->minutes)
       (add-pcts-to-day task-id->pcts minutes-worked)))

(defn- fill-days [day->events task-id->minutes task-id->pcts minutes-worked]

  ;; apply add-user-times-to-day to the values of the map, but this function
  ;; requires the date as well. As dates are the map keys, map a function
  ;; that takes and returns [key new-value] and turn those back into a map
  (letfn [(add-user-times [[day events]]
            [day
             (add-user-times-to-day day events task-id->minutes
                                    task-id->pcts minutes-worked)])]
    (->> day->events
         (map add-user-times)
         (into {})))
  ;; (->>
  ;;  events
  ;;  (mapcat split-event-at-midnight)
  ;;  (group-by event-day)
  ;;  (merge (reduce #(assoc %1 %2 []) {} days-covered))
  ;;  (#(map % days-covered)) ; keep only those in days-covered, becomes list of lists
  ;;  (map #(sort-by :start-time))
  ;;  (map #(add-minutes-to-day % task-id->minutes))
  ;;  (map #(fill-day-by-percents % task-id->pcts minutes-worked))
  ;;  ;; vals
  ;;  flatten)
  )

(defn transfer-gc-to-tc [{:keys [start-date
                                 end-date
                                 calendar-id
                                 client-secrets-file
                                 data-store-dir
                                 tc-api-token
                                 hours-worked
                                 include-weekends?
                                 arguments]}]
  (let [{:keys [task-id->minutes task-id->pcts]} (task-id->time-from-arguments arguments)
        minutes-worked (round (* hours-worked 60))
        days-covered (days-between-inclusive start-date end-date include-weekends?)
        start-datetime (first-second-of-date start-date)
        end-datetime (last-second-of-date end-date)
        events (gc/get-events client-secrets-file data-store-dir calendar-id start-datetime end-datetime)
        day->events (create-day-event-maps events days-covered)
        day->filled-events (fill-days day->events task-id->minutes task-id->pcts minutes-worked)
        all-events (flatten (vals day->filled-events))
        ;; map, we do not appear to be able to post multiple events in one request
        posted-days (map #(tc/summary-post-event-to-tc tc-api-token %) all-events)
        {successes true failures false} (group-by :ok posted-days)]
    ;; TODO log failures as they happen, or quit on error
    (when (some? failures) (json/pprint failures))
    (println (format "%d successes, %d failures" (count successes) (count failures)))))

(defn -main [& args]
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (transfer-gc-to-tc args))))
