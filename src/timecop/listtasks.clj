(ns timecop.listtasks
  (:require [timecop.cli :as cli]
            [timecop.tc-util :as tc]
            [clojure.string :as str]))

(defn usage
  [options-summary]
  (->>
   ["Usage:"
    "  list-tasks [-t TC_API_TOKEN]"
    ""
    "Named arguments:"
    options-summary
    ""
    "Please refer to the README at for more information"]
   (str/join \newline) ))

(def option-specs
  [["-t" "--tc-api-token TC_API_TOKEN"
    "TimeCamp API token. See README for more information."
    :default (System/getenv "TC_API_TOKEN") :default-desc "$TC_API_TOKEN"]
   ["-h" "--help" "Show this help and exit."]])

(defn ^:private validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [{:keys [arguments options errors summary]}
        (cli/parse-opts cli-args option-specs #_(:summary-fn cli/summary-fn))]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (cli/error-msg errors)}

      :else
      {:args (assoc options :arguments arguments)})))

(defn list-tasks [args]
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      {:exit-message exit-message :ok? ok?}
      (let [{:keys [tc-api-token]} args
            {:keys [message ok?]} (tc/list-tasks tc-api-token)]
        {:exit-message message :ok? ok?}))))
