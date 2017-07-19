(ns timecop.simplefillcli
  (:require [clojure.string :as str]
            [timecop.businesstime :as bt]
            [timecop.cli :as cli]
            [timecop.filldays :refer [transfer-gc-to-tc]])
  (:import [java.time DayOfWeek LocalDate]))

(def ^:const INPUT_RE (re-pattern (format "%s\n|%s" bt/HOURS_MINS_RE bt/PCT_RE)))

(def ^:private option-specs
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

(def ^:private pos-args-summary-lines
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

(def ^:private pos-args-summary-lines
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

(def ^:private pos-args-summary
  (str/join
   \newline
   [(cli/indent "TASK_TIME_PAIR..." 2)
    (str/join \newline (map #(cli/fit-to-line-length 6 %) pos-args-summary-lines))]))

(defn ^:private usage [options-summary]
  (->> ["Usage: "
        "  fill-days [-s START_DATE] [-e END_DATE] [--hours-worked HOURS]"
        "            [-c CLIENT_SECRETS_FILE] [-d DATA_STORE_DIR] [-t TC_API_TOKEN]"
        "            [-i CALENDAR_ID] [-w] [TASK_TIME_PAIR...]"
        ""
        "Positional arguments:"
        pos-args-summary
        ""
        "Named arguments:"
        options-summary
        ""
        "Please refer to the README at for more information"]
       (str/join \newline)))

(defn ^:private args-invalid? [args]
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

(defn ^:private validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [{:keys [arguments options errors summary]}
        (cli/parse-opts cli-args option-specs :summary-fn cli/summary-fn)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      ;; TODO provide useful messages
      (args-invalid? arguments)
      {:exit-message (cli/error-msg ["Invalid args"])}

      errors ; errors => exit with description of errors
      {:exit-message (cli/error-msg errors)}

      (.isAfter (:start-date options) (:end-date options))
      {:exit-message (cli/error-msg ["End date must be after start date"])}

      :else
      {:args (assoc options :arguments arguments)})))

(defn fill-days
  "Attempt to pull events from Google Calendar and process them into TimeCamp.
  Return a map containing :exit-message, for print on exit, and :ok?, a boolean
  indicating success or failure."
  [args]
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      {:exit-message exit-message :ok? ok?}
      (do
        (transfer-gc-to-tc args)
        {:exit-message "Transferred successfully" :ok? true}))))
