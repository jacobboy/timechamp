(ns timechamp.filldayscli
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s]
            [timechamp.businesstime :as bt]
            [timechamp.cli :as cli]
            [timechamp.filldays :refer [transfer-gc-to-tc]])
  (:import [java.time DayOfWeek LocalDate]))

(def ^:const INPUT_RE (re-pattern (format "%s\n|%s" bt/HOURS_MINS_RE bt/PCT_RE)))

(defn expand-home [s]
  (if (.startsWith s "~")
    (clojure.string/replace-first s "~" (System/getProperty "user.home"))
    s))

(def ^:private option-specs
  [["-s" "--start-date START_DATE" "Start date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(LocalDate/parse %) :default (LocalDate/now)
    :default-desc "Current date"]

   ["-e" "--end-date END_DATE" "End date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(LocalDate/parse %) :default (LocalDate/now)
    :default-desc "Current date"]

   [nil "--hours-worked HOURS"
    (str "Hours worked per day. Must be less than 15, "
         "as events are moved to begin at 9am.")
    :parse-fn #(. Double parseDouble %1)
    :validate [#(< 0 % 15)
               ;; Lame, >15 not compatible with 9:00 start of day
               "Hours worked must be less than 15."]
    :default 8]

   ["-g" "--gc-secrets-file GOOGLE_SECRETS_FILE"
    "Path to Google client secrets JSON file. See README for more information."
    :default (or (System/getenv "TIMECHAMP_GOOGLE_SECRETS_FILE")
                 (expand-home "~/.timechamp/google-secrets.json"))
    :default-desc (str "$TIMECHAMP_GOOGLE_SECRETS_FILE or "
                       "~/.timechamp/google-secrets.json")]

   ["-d" "--data-store-dir DATA_STORE_DIR"
    "Path to the folder where the Google client will save its auth information
     between uses. See README for more information."
    :default (or (System/getenv "TIMECHAMP_DATA_STORE_DIR")
                 (expand-home "~/.timechamp/data/"))
    :default-desc "$TIMECHAMP_DATA_STORE_DIR or ~/.timechamp/data/"]

   ["-t" "--tc-api-token TIMECAMP_API_TOKEN"
    "TimeCamp API token. See README for more information."
    :default (System/getenv "TIMECAMP_API_TOKEN")
    :default-desc "$TIMECAMP_API_TOKEN"]

   ["-c" "--calendar-id CALENDAR_ID"
    (str "ID of the Google calendar to read events from. Unless you've created "
         "multiple calendars on the account and know which you want, the default "
         "is what you want.")
    :default "primary"]

   ["-w" "--include-weekends" "Create events for weekends."
    :id :include-weekends? :default false]

   ["-h" "--help"]])

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
        "  fill-days [-s START_DATE] [-e END_DATE] [--hours-worked HOURS]"
        "            [-g GOOGLE_SECRETS_FILE] [-d DATA_STORE_DIR]"
        "            [-t TIMECAMP_API_TOKEN] [-i CALENDAR_ID] [-w]"
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


(defn ^:private create-dir-if-not-exists [dir]
  [])

;; needed because tools.cli's validation does not run if default is used
(defn ^:private paths-exist? [options]
  (let [opt->path (select-keys options [:gc-secrets-file #_:data-store-dir])]
    (->> opt->path
         (filter (fn [[_ path]] (not (.exists (io/file path)))))
         (map (fn [[opt path]] (str (name opt) " at " path " is missing"))))))

(defn ^:private args-invalid? [args]
  (when-not
      (and
       (some? args)
       (coll? args) ; is this a stupid check?
       ;; (pos? (count args))  ;; can be empty
       (even? (count args))  ;; must be key value pairs (allows empty)
       ;; each key should be an int, TimeCamp IDs are ints
       (every? some? (map #(re-find #"^\d*$" %) (take-nth 2 args)))
       ;; each value should be an hours/minutes combo or a percent
       (every? some? (map #(re-find INPUT_RE %) (take-nth 2 (rest args)))))
    ;; TODO useful error messages
    ["Time arguments do not match the required patterns"]))

(defn ^:private validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [cli-args (or cli-args [])
        {:keys [arguments options errors summary]} (cli/parse-opts
                                                    cli-args option-specs
                                                    :summary-fn cli/summary-fn)
        invalid-opts (opts-invalid? options)
        uncreated-data-store (create-dir-if-not-exists (:data-store-dir options))
        missing-paths (paths-exist? options)
        invalid-args (args-invalid? arguments)
        errors (concat errors invalid-opts uncreated-data-store
                       missing-paths invalid-args)]
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
                calendar-id
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
                                                     calendar-id
                                                     gc-secrets-file
                                                     data-store-dir
                                                     tc-api-token
                                                     hours-worked
                                                     include-weekends?
                                                     arguments)]
        {:exit-message message :ok? ok?}))))
