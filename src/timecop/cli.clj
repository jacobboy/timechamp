(ns timecop.cli
  (:require [clojure.data :as data]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import java.lang.System))

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
                     (map (partial cli/make-summary-part show-defaults?) specs))
          lines (map format-opt-line parts)]
      (str/join "\n\n" lines))
    ""))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit [status msg] (println msg) (System/exit status))

(def parse-opts
  "See tools.cli/parse-opts"
  cli/parse-opts)
