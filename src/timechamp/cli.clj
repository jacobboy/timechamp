(ns timechamp.cli
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.data :as data]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [timechamp.config :as config]
            [clojure.set :as set])
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

(defn exit [status msg] (println msg) #_(System/exit status))

(defn ^:private option-spec->tools-cli
  [[id
    [sopt short-desc desc
     & {:keys [config? default default-desc] :as options} :as spec]]]
  (let [id-name (name id)
        lopt (str "--" id-name (when short-desc (str " " short-desc)))
        default-desc (or default-desc (some-> default str))
        default-desc (cond->>
                         default-desc
                       config? (str id-name " config value or "))
        new-options (->
                     options
                     (dissoc :config?)
                     (assoc :default-desc default-desc)
                     seq
                     ;; turn map into kwargs
                     (->> (apply concat)))]
    (vec (concat [sopt lopt desc] new-options))))

(defn ^:private option-spec->config-spec
  [[nm [_ _ _ & {:keys [config? default id parse-fn]}]]]
  (when config? [nm default (or id nm) parse-fn]))

(defn parse-opts [args option-specs]
  "See tools.cli/parse-opts. Defaults to config variables as specified.
  TODO: Add real documentation of the schema."
  (let [filter-nil-vals #(into {} (filter (comp some? val) %))

        cli-specs (mapv option-spec->tools-cli option-specs)

        names->ids (->> option-specs
                        (fmap (fn [[_ _ _ & {:keys [id]}]] id))
                        filter-nil-vals)

        {:keys [arguments options errors summary]}
        (cli/parse-opts args cli-specs
                        :no-defaults true
                        :summary-fn summary-fn
                        :in-order true)

        ;; _ (set! config/CONFIG_FILE_PATH
        ;;         (or (:config? options) config/DEFAULT_CONFIG_FILE_PATH))

        defaults (-> option-specs
                     (->> (fmap (fn [[_ _ _ & {:keys [default]}]] default)))
                     filter-nil-vals
                     (set/rename-keys names->ids))

        config-path (or (:config? options) config/DEFAULT_CONFIG_FILE_PATH)

        parse-fns (->> option-specs
                       (fmap (fn [[_ _ _ & {:keys [parse-fn]}]] parse-fn))
                       filter-nil-vals)

        ;; TODO option-spec->config-spec no longer really needed?
        ;; see names->ids, defaults, parse-fns
        config (binding [config/CONFIG_FILE_PATH config-path]
                   (as-> option-specs _
                     (map option-spec->config-spec _)
                     (filter some? _)
                     ;; (apply dissoc _ (keys options))
                     (map first _)
                     ;; TODO pass parse-fns in here?
                     (zipmap _ (map config/get-config-var _))
                     (filter-nil-vals _)
                     (into {}
                           (for [[nm val] _
                                 :let [parse-fn (parse-fns nm)]]
                             (if (some? parse-fn)
                               [nm (parse-fn val)]
                               [nm val])))
                     (set/rename-keys _ names->ids)))

        all-options (merge defaults config options)
        ;; TODO: validation
        ]
    {:arguments arguments
     :options all-options
     :errors errors
     :summary summary}))
