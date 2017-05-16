(ns timecop.core
  (:gen-class)
  (:require [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [timecop.date-helpers
             :refer
             [beginning-of-next-day
              end-of-day
              gc-duration
              gc-date-to-tc-date
              gc-date-to-tc-time]]
            [timecop.gc-util :as gc]
            [timecop.tc-util :as tc])
  (:import com.google.api.client.util.DateTime
           [com.google.api.services.calendar.model Event EventDateTime]
           java.lang.System))

;; (defmulti canonical-event #(:source (meta %)))

;; (defmethod canonical-event :gc [gc-event]
;;   (let [event (select-keys gc-event ["start" "end" "summary"])]
;;     event))

;; (defmethod canonical-event :tc [tc-event]
;;   (tc-event))

(defn gc-event-to-tc-events [^Event gc-event]
  (let [start (.. gc-event getStart getDateTime)
        end (.. gc-event getEnd getDateTime)
        start-date (gc-date-to-tc-date start)
        end-date (gc-date-to-tc-date end)
        summary (. gc-event getSummary)
        external-id (. gc-event getId)
        duration (gc-duration start end)]
    (if (= start-date end-date)
      [{:date start-date
        :start_time (gc-date-to-tc-time start)
        :end_time (gc-date-to-tc-time end)
        :duration (str duration)
        ;; timecamp couldn't deal with the quotes in json, use yaml to avoid quotes
        :note (yaml/generate-string {:summary summary :external-id external-id}
                                    :dumper-options {:flow-style :block})
        :task_id tc/MEETINGS_TASK_ID}]
      (let [gc-event-first (.setEnd (.clone gc-event)
                                    (.setDateTime (EventDateTime.) (end-of-day start)))
            gc-event-rest (.setStart (.clone gc-event)
                                     (.setDateTime (EventDateTime.) (beginning-of-next-day start)))]
        (flatten [(gc-event-to-tc-events gc-event-first)
                  (gc-event-to-tc-events gc-event-rest)])))))

(defn yyyy-MM-dd? [date]
  (boolean (re-matches #"^\d{4}-\d{2}-\d{2}$" date)))

(def cli-options
  ;; TODO better input validation on start-date and end-date
  [["-s" "--start-date START_DATE" "Start date (inclusive) in yyyy-MM-dd format"
    :validate [yyyy-MM-dd? "Start date must be in yyyy-MM-dd format"]]
   ["-e" "--end-date END_DATE" "End date (inclusive) in yyyy-MM-dd format"
    :validate [yyyy-MM-dd? "End date must be in yyyy-MM-dd format"]]
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

      :else
      {:args options})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn post-tc [tc-api-token tc-event]
  (let [{:keys [status body] :as response} (tc/post-tc tc-api-token tc-event)]
    {:ok (>= 300 status)
     :status status
     :body (json/read-str body)
     :tc-event tc-event}))

(defn do-the-thing [{:keys [start-date end-date calendar-id
                            client-secrets-loc data-store-dir tc-api-token]}]
  (let [cal-list "jacob@picwell.com"
        time-min (DateTime. start-date)
        time-max (DateTime. end-date)
        gc-events (gc/get-events client-secrets-loc data-store-dir calendar-id time-min time-max)
        tc-events (mapcat gc-event-to-tc-events gc-events)
        {successes true failures false} (group-by :ok (map #(post-tc tc-api-token %) tc-events))]
    (when (some? failures) (json/pprint failures))
    (print (format "%d successes, %d failures" (count successes) (count failures)))))


(defn -main [& args]
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do-the-thing args))))
