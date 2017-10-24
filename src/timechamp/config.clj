(ns timechamp.config
  (:require [yaml.core :as yaml]
            [clojure.java.io :as io]))

(def ^:const DEFAULT_CONFIG_FILE_PATH (str (System/getProperty "user.home")
                                           "/.timechamp/config.yaml"))

;; Set by the CLI parser
(def ^:dynamic CONFIG_FILE_PATH nil)

(defn get-config []
  (let [config-file (io/file CONFIG_FILE_PATH)]
    (yaml/parse-string (slurp config-file))))

(defn get-config-section [section]
  "Returns the specified section of config, raising an exception if not found
  or empty."
  (let [config (get-config)
        config-section (section config)]
    (if (nil? config-section)
      (ex-info (str "Config section " section " not found"))
      config-section)))

(defn get-config-values
  "Provide a section keyword, and keywords or vectors of [keyword default] for
  values within that section. Returns a vector of values in the order provided.
  Throws an exception if any keys are not found, or if any defaults are nil."
  [section-name & kwd-or-kwd-default-vecs]
  {:post [(not-any? nil? %)]}
  (let [section (get-config-section section-name)
        stuff (->> kwd-or-kwd-default-vecs
             (map
              (fn [kwd-or-kwd-default]
                (let [getter (if (vector? kwd-or-kwd-default)
                               (partial apply get)
                               get)]
                  (getter section kwd-or-kwd-default))))
             #_(into {}))]
    stuff))

(defn get-config-var
  ([varname cast-fn]
   (let [k (keyword varname)]
     (some-> (get-config)
             k
             cast-fn)))
  ([varname]
   (get-config-var varname identity)))
