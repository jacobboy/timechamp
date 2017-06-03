(ns timecop.core
  (:gen-class)
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.data :refer :all]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [timecop.businesstime :refer :all]
            [timecop.gc-util :as gc]
            [timecop.tc-util :as tc])
  (:import java.lang.System
           java.time.LocalDate))

(defn yyyy-MM-dd? [date]
  (boolean (re-matches #"^\d{4}-\d{2}-\d{2}$" date)))

(defn task-id->pcts-from-args
  [args]
  (reduce-kv #(assoc %1 %2 (Double. %3)) {} (apply hash-map args)))

(def required-opts #{:start-date :end-date})

;; TODO Env var defaults make the --help output un-pretty when the env vars are set
(def cli-options
  [["-s" "--start-date START_DATE" "Required. Start date (inclusive) in yyyy-MM-dd format"
    ;; going through LocalDate ended up much simpler than trying to use LocalDateTime directly
    :parse-fn #(first-second-of-date (LocalDate/parse %))
    ]
   ["-e" "--end-date END_DATE" "Required. End date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(last-second-of-date (LocalDate/parse %))
    ]
   ["-i" "--calendar-id" "ID of the Google calendar to read events from. Default: primary"
    :default "primary"]
   ["-c" "--client-secrets-loc CLIENT_SECRETS"
    "Path to Google client secrets JSON file. Default: TIMECOP_SECRETS_LOC env variable."
    :default (System/getenv "TIMECOP_SECRETS_LOC")]
   ["-d" "--data-store-dir DATA_STORE_DIR"
    "Path where app will store credentials. Default: TIMECOP_CREDENTIALS_LOC env variable."
    :default (System/getenv "TIMECOP_CREDENTIALS_LOC")]
   ["-t" "--tc-api-token"
    "TimeCamp API token. Default: TC_API_TOKEN env variable."
    :default (System/getenv "TC_API_TOKEN")]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Blindly shove Google calendar events into TimeCamp"
        ""
        "Usage: program-name -s start-date -e end-date -i calendar-id -c client-secrets-loc -d data-stor-dir TASK_ID_PERCENT_PAIRS"
        ""
        (str/join
         ""
         ["Where TASK_ID_PERCENT_PAIRS is a sequence of pairs of TimeCamp task ids and percents, "
          "e.g. `12345 .7 32456 .3`. "
          "Percentages must be positive but need not sum to 1; "
          "for example, specifying only half of an 8 hour day is allowed. "
          "For more information on task ids, see the README. "
          "Start and end dates are required, and the range is inclusive. "])
        ""
        "Options:"
        options-summary
        ""
        "Please refer to the manual page for more information."]
       (str/join \newline)))

(defn missing-required
  "Returns any required options missing from the supplied opts"
  [opts]
  (let [[only-opts only-req both] (diff (set (keys opts)) required-opts)]
    only-req))

(defn args-invalid? [args]
  (not
   (and
    ;; (some? args)
    ;; (seq? args)
    (even? (count args))
    (pos? (count args))
    (try
      ;; check no negative percentages specified
      (let [task-id->pcts (task-id->pcts-from-args args)]
        (every? pos? (vals task-id->pcts)))
        ;; (= 1 (reduce #(+ %1 (second %2)) 0 args-map)))
      (catch Exception e false)))))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [{:keys [arguments options errors summary]} (parse-opts cli-args cli-options)]
    (cond
      (or (:help options) ; help => exit OK with usage summary
          (seq (missing-required options)) ; use seq instead of (not (empty? ,,,))?
          (args-invalid? arguments))
      ;; TODO provide useful messages
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; require start-date and end-date parameters
      (not-every? (partial contains? options) [:start-date :end-date])
      {:exit-message (error-msg ["Must provide both start and end date as yyyy-MM-dd values"])}

      (not (.isBefore (:start-date options) (:end-date options)))
      {:exit-message (error-msg ["End date must be after start date"])}

      :else
      {:args (assoc options :task-id-pcts (task-id->pcts-from-args arguments))})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

;; (defn handle-events [events]
;;   )

;; (defn handle-responses
;;   [responses]

;;   )

(defn event-day [event]
  (.toLocalDate (:start-time event)))

(defn do-the-thing [{:keys [start-date end-date calendar-id
                            client-secrets-loc data-store-dir tc-api-token
                            task-id-pcts]}]
  (let [events (gc/get-events client-secrets-loc data-store-dir
                              calendar-id start-date end-date)
        split-events (mapcat split-event-at-midnight events)
        day-events (group-by event-day split-events)
        day-filled-events (fmap #(fill-day-by-percents %1 task-id-pcts 8) day-events)
        ;; filled-events (fill-day-by-percents split-events task-id-pcts 8)
        filled-events (flatten (vals day-filled-events))
        response-summaries (fmap #(tc/summary-post-event-to-tc tc-api-token %) filled-events)
        {successes true failures false} (group-by :ok response-summaries)]
    ;; TODO log failures as they happen?
    (when (some? failures) (json/pprint failures))
    (print (format "%d successes, %d failures" (count successes) (count failures)))))

(defn -main [& args]
  (println args)
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do-the-thing args))))
