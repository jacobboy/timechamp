(ns timechamp.util
  (:require [clojure.string :as str]))

(defn expand-home [s]
  (if (.startsWith s "~")
    (str/replace-first s "~" (System/getProperty "user.home"))
    s))
