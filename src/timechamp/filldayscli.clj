(ns timechamp.filldayscli
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s]
            [timechamp.businesstime :as bt]
            [timechamp.cli :as cli]
            [timechamp.config :as config]
            [timechamp.eventsources :as sources]
            [timechamp.filldays :refer [transfer-gc-to-tc]]
            [timechamp.util :refer [expand-home]])
  (:import java.time.LocalDate))

(def ^:const INPUT_RE (re-pattern (format "%s\n|%s" bt/HOURS_MINS_RE bt/PCT_RE)))

(def ^:private option-specs
  {:start-date
   ["-s" "START_DATE" "Start date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(LocalDate/parse %)
    (LocalDate/now)
    :default-desc "Current date"]

   :end-date
   ["-e" "END_DATE" "End date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(LocalDate/parse %)
    :default (LocalDate/now)
    :default-desc "Current date"]

   :hours-worked
   [nil "HOURS"
    (str "Hours worked per day. Must be less than 15, "
         "as events are moved to begin at 9am.")
    :parse-fn #(. Double parseDouble %)
    :validate [#(< 0 % 15) ; Lame, >15 not compatible with 9:00 start of day
               "Hours worked must be less than 15."]
    :default 8
    :config? true]

   :include-weekends
   ["-w" nil "Create events for weekends."
    :id :include-weekends?
    :config? true]

   :data-store-dir
   ["-d" "DATA_STORE_DIR"
    (str "Path to the folder where the Google client will save its auth "
         "information between uses. See README for more information.")
    :default "~/.timechamp/data/"
    :config? true]

   :gc-meeting-id
   ["-m" "MEETING_ID"
    "TimeCamp ID to use for Google calendar events."
    :parse-fn str
    :config? true]

   :gc-calendar-id
   ["-i" "CALENDAR_ID"
    (str "ID of the Google calendar to read events from. Unless you've created "
         "multiple calendars on the account and know which you want, the default "
         "is what you want.")
    :default "primary"
    :config? true]

   :gc-secrets-file
   ["-g" "GOOGLE_SECRETS_FILE"
    "Path to Google client secrets JSON file. See README for more information."
    :default "~/.timechamp/google-secrets.json"
    :config? true]

   :tc-api-token
   ["-t" "TIMECAMP_API_TOKEN"
    "TimeCamp API token. See README for more information."
    :config? true]

   :config
   ["-c" "CONFIG_FILE" "Path to config file."
    :default config/DEFAULT_CONFIG_FILE_PATH]

   :help
   ["-h" nil "Show this help and exit."]})

(def ^:private pos-args-summary-lines
  ["Optional."
   ""
   "Ex: 1234 1h 2345 1h30m 3456 80% 4567 20%"
   ""
   "A TASK_TIME_PAIR is a space-separated pair of a (numeric) TimeCamp
    task id and either an absolute length time or a percentage of free
    time. TimeChamp will first create events from any tasks with absolute
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
        "  fill-days [-s START_DATE] [-e END_DATE]"
        "            [--hours-worked HOURS] [-w] [-d DATA_STORE_DIR]"
        "            [-i CALENDAR_ID] [-g GOOGLE_SECRETS_FILE] "
        "            [-t TIMECAMP_API_TOKEN]"
        "            [TASK_TIME_PAIR...]"
        ""
        "Positional arguments:"
        pos-args-summary
        ""
        "Named arguments:"
        options-summary
        ""
        "Please refer to the README at for more information"]
       (str/join \newline)))

;; needed because tools.cli's validation does not run if default is used
(defn ^:private opts-invalid? [opts]
  (let [api-token-missing (str/blank? (:tc-api-token opts))
        start-after-end (.isAfter (:start-date opts) (:end-date opts))]
    (->>
     [[api-token-missing (str "TimeCamp API token is missing - did you set "
                              "$TIMECHAMP_API_TOKEN instead of  "
                              "$TIMECAMP_API_TOKEN?")]
      [start-after-end "Start date must not be after end date"]]

     (filter first)
     (map second))))

;; needed because tools.cli's validation does not run if default is used
(defn ^:private paths-exist? [options]
  (let [opt->path (select-keys options [:gc-secrets-file #_:data-store-dir])]
    (->> opt->path
         (filter (fn [[_ path]] (not (.exists (io/file (expand-home path))))))
         (map (fn [[opt path]] (str (name opt) " at " path " is missing"))))))

(defn ^:private valid-tc-id? [time-id]
  ;; TimeCamp IDs are ints, so each key should be an int
  (some? (re-find #"\d+$" time-id)))

(defn ^:private invalid-time-ids [time-ids]
  (->> time-ids
       (remove (apply some-fn valid-tc-id? sources/VALID_ID_PREDS))
       (map #(str % " is not a valid TC id"))))

(defn ^:private valid-duration? [duration]
  ;; each value should be an hours/minutes combo or a percent
  (some? (re-find INPUT_RE duration)))

(defn ^:private invalid-durations [durations]
  (->> durations
       (remove valid-duration?)
       (map #(str % " not a valid time"))))

(defn ^:private args-invalid? [args]
  ;; TODO useful error messages
  (if
      (odd? (count args)) ["Even number of arguments required (key-value pairs)"]
      (let [time-ids (take-nth 2 args) ; args 1, 3 ,5, etc
            durations (take-nth 2 (rest args)) ; args 2, 4, 6, etc
            bad-ids (invalid-time-ids time-ids)
            bad-durations (invalid-durations durations)]
        (if (or (seq bad-ids) (seq bad-durations))
          (concat bad-ids bad-durations)
          []))))

(defn ^:private validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [cli-args (or cli-args [])
        {:keys [arguments options errors summary]} (cli/parse-opts
                                                    cli-args option-specs)
        invalid-opts (opts-invalid? options)
        missing-paths (paths-exist? options)
        invalid-args (args-invalid? arguments)
        errors (concat errors invalid-opts missing-paths invalid-args)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      (seq errors) ; errors => exit with description of errors
      {:exit-message (cli/error-msg errors)}

      :else
      {:args (assoc options :arguments arguments)})))

(s/defn fill-days :- {:exit-message s/Str :ok? (s/maybe s/Bool)}
  "Attempt to pull events from Google Calendar and process them into TimeCamp.
  Return a map containing :exit-message, for print on exit, and optionally :ok?,
  a boolean indicating success or failure."
  [args :- (s/maybe [s/Str])]
  (let [{exit-message :exit-message
         ok? :ok?
         ;; this got verbose because I wanted a simple definition for
         ;; transfer-gc-to-gc, while still using Schema.  Could be I
         ;; just need to learn schema better.
         args :args} (validate-args args)
        {:keys [start-date
                end-date
                gc-calendar-id
                gc-meeting-id
                gc-secrets-file
                data-store-dir
                tc-api-token
                hours-worked
                include-weekends?
                arguments]} args]
    (if exit-message
      {:exit-message exit-message :ok? ok?}
      (let [{:keys [message ok?]} (transfer-gc-to-tc start-date
                                                     end-date
                                                     gc-calendar-id
                                                     gc-meeting-id
                                                     gc-secrets-file
                                                     data-store-dir
                                                     tc-api-token
                                                     hours-worked
                                                     include-weekends?
                                                     arguments)]
        {:exit-message message :ok? ok?}))))
