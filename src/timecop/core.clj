(ns timecop.core
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [schema.core :as s]
            [timecop.gc-util :as gc]
            [timecop.tc-util :as tc]
            [timecop.schema :refer :all])
  (:import java.lang.System
           [java.time LocalDate LocalDateTime]
           timecop.schema.CanonicalEvent))

(defn yyyy-MM-dd? [date]
  (boolean (re-matches #"^\d{4}-\d{2}-\d{2}$" date)))

(s/defn first-second-of-date [date :- LocalDate]
  (.atStartOfDay date))

(s/defn last-second-of-date [date :- LocalDate]
  (.atTime date 23 59 59))

(def cli-options
  ;; TODO better input validation on start-date and end-date
  [["-s" "--start-date START_DATE" "Start date (inclusive) in yyyy-MM-dd format"
    ;; going through local date ended up much simpler than trying to use LocalDateTime directly
    :parse-fn #(first-second-of-date (LocalDate/parse %))
    ; :validate [yyyy-MM-dd? "Start date must be in yyyy-MM-dd format"]
    ]
   ["-e" "--end-date END_DATE" "End date (inclusive) in yyyy-MM-dd format"
    :parse-fn #(last-second-of-date (LocalDate/parse %))
    ; :validate [yyyy-MM-dd? "End date must be in yyyy-MM-dd format"]
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
        "Usage: program-name -s start-date -e end-date -i calendar-id -c client-secrets-loc -d data-stor-dir"
        ""
        "Options:"
        options-summary
        ""
        "Please refer to the manual page for more information."]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; require start-date and end-date parameters
      (not-every? (partial contains? options) [:start-date :end-date])
      {:exit-message (error-msg ["Must provide both start and end date as yyyy-MM-dd values"])}

      (not (.isBefore (:start-date options) (:end-date options)))
      {:exit-message (error-msg ["End date must be after start date"])}

      :else
      {:args options})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

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

(defn do-the-thing [{:keys [start-date end-date calendar-id
                            client-secrets-loc data-store-dir tc-api-token]}]
  (let [events (gc/get-events client-secrets-loc data-store-dir calendar-id start-date end-date)
        split-events (mapcat split-event-at-midnight events)
        {successes true failures false}
        (group-by :ok (map #(tc/summary-post-tc-entry tc-api-token %) split-events))]
    ;; TODO print failures as they happen?
    (when (some? failures) (json/pprint failures))
    (print (format "%d successes, %d failures" (count successes) (count failures)))))

(defn -main [& args]
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do-the-thing args))))
