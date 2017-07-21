(ns timecop.cli
  (:require [clojure.data :as data]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import java.lang.System))

(defn indent [line indent-level]
  (pp/cl-format nil "~vA~A" indent-level "" line))

(defn fit-to-line-length
  "Format a line to within the desired line length, adding line breaks as
  necessary."
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

(defn ^:private format-opt-line [part]
  ;; Kind of weird logic because the data comes from cli/make-summary-part,
  ;; which outputs [name description] or [name default description]
  (case (count part)
    2 (let [[name description] part
            fmt "  ~A\n~A"]
        (pp/cl-format nil fmt name (fit-to-line-length 6 description)))
    3 (let [[name default description] part
            fmt "  ~A\n      \u001B[4mDefault: ~A\u001B[0m\n~A"]
        (if (empty? default)
          ;; if has no default but others did, skip this default line anyway
          (format-opt-line [name description])
          (pp/cl-format nil
                        fmt
                        name
                        default
                        (fit-to-line-length 6 description))))))

(defn summary-fn
  "An alternative summary function for use with `parse-opts`, should not be
  called directly. Provides summaries in the form

    -s, --start-date START_DATE
        Default: Today
        Start date (inclusive) in yyyy-MM-dd format

  and adds ANSI control characters to underline the default line. I'd prefer
  italics but that sequence is frequently unsupported or inverted colors."
  [specs]
  (if (seq specs)
    (let [show-defaults? (some #(and (:required %) (contains? % :default)) specs)
          parts (map #(map str/triml %)
                     (map (partial cli/make-summary-part show-defaults?) specs))
          lines (map format-opt-line parts)]
      (str/join "\n\n" lines))
    ""))

(defn error-msg [errors]
  "Construct an error message with the provided seqence of error descriptions."
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit [status msg] (println msg) (System/exit status))

(def parse-opts
  "See tools.cli/parse-opts"
  cli/parse-opts)
