(ns timecop.core
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [timecop.cli :as cli]
            [timecop.listtasks :as listtasks]
            [timecop.simplefillcli :as simplefill]))

(defn usage
  [subcommand-names options-summary]
  (->>
   ["Usage:"
    "  java -jar timecamp.jar <subcommand> [<args>]"
    ""
    "Available commands:"
    (map #(cli/indent % 2) subcommand-names)
    ""
    "Named arguments:"
    options-summary
    ""
    "Please refer to the README at for more information"]
   flatten
   (str/join \newline)))

(def subcommand-options-specs
  [["-h" "--help" "Show this help and exit."]])

(def subcommand-name->subcommand
  {"fill-days" simplefill/fill-days
   "list-tasks" listtasks/list-tasks})

(defn parse-args [args]
  (let [{:keys [arguments options errors summary]}
        (tools.cli/parse-opts args subcommand-options-specs :in-order true)
        [subcommand-name & subcommand-args] arguments
        subcommand (subcommand-name->subcommand subcommand-name)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage (keys subcommand-name->subcommand) summary)
       :ok? true}

      subcommand
      (let [{:keys [exit-message ok?]} (subcommand subcommand-args)]
        ;; subcommands may return :exit-message, and :ok? if ok
        {:exit-message exit-message :ok? ok?})

      :else
      {:exit-message (usage summary) :ok? false})))

(defn -main [& args]
  (let [{:keys [exit-message ok?]} (parse-args args)]
    (cli/exit (if ok? 0 1) exit-message)))
