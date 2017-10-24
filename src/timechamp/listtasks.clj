(ns timechamp.listtasks
  (:require [clojure.string :as str]
            [schema.core :as s]
            [timechamp.cli :as cli]
            [timechamp.config :as config]
            [timechamp.tc-util :as tc]))

(defn ^:private usage
  [options-summary]
  (->>
   ["Usage:"
    "  list-tasks [-t TIMECAMP_API_TOKEN]"
    ""
    "Named arguments:"
    options-summary
    ""
    "Please refer to the README at for more information"]
   (str/join \newline) ))

(def ^:private option-specs
  [:tc-api-token ["-t" "TIMECAMP_API_TOKEN"
                  "TimeCamp API token. See README for more information."
                  :config? true]
   :help
   ["-h" nil "Show this help and exit."]])

(defn ^:private opts-invalid? [opts]
  (let [api-token-missing (str/blank? (:tc-api-token opts))]
    (->>
     [[api-token-missing (str "TimeCamp API token is missing - did you set "
                              "$TIMECHAMP_API_TOKEN instead of  "
                              "$TIMECAMP_API_TOKEN?")]]
     (filter first)
     (map second))))

(defn ^:private validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [cli-args]
  (let [{:keys [arguments options errors summary]}
        (cli/parse-opts cli-args option-specs #_(:summary-fn cli/summary-fn))
        invalid-opts (opts-invalid? options)
        errors (concat errors invalid-opts)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      (seq errors) ; errors => exit with description of errors
      {:exit-message (cli/error-msg errors)}

      :else
      {:args (assoc options :arguments arguments)})))

(s/defn list-tasks :- {:exit-message s/Str :ok? (s/maybe s/Bool)}
  [args :- [s/Str]]
  (let [{:keys [args exit-message ok?]} (validate-args args)]
    (if exit-message
      {:exit-message exit-message :ok? ok?}
      (let [{:keys [tc-api-token]} args
            {:keys [message ok?]} (tc/list-tasks tc-api-token)]
        {:exit-message message :ok? ok?}))))
