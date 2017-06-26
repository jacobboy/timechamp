(ns timecop.tc-util
  (:require [clj-http.client :as client]
            [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [schema.core :as s])
  (:import java.time.format.DateTimeFormatter
           java.time.LocalDateTime
           timecop.schema.CanonicalEvent))

(def ^:const TC_URL_TEMPLATE "https://www.timecamp.com/third_party/api/%s/format/json/api_token/%s")

(def ^:const TASK_IDS {:meeting "9238867"})

(defn tc-get-url [endpoint api-token]
  (format TC_URL_TEMPLATE endpoint api-token))

(defn tc-post-url [endpoint api-token]
  (format TC_URL_TEMPLATE endpoint api-token))

(defn get-users [api-token]
  (let [url (tc-get-url "users" api-token)
        response (client/get url)]
    (json/read-str (:body response))))

(defn get-user-id-from-email [api-token email]
  (let [users (get-users api-token)
        matching-users (filter #(= email (% "email")) users)]
    ((first matching-users) "user_id")))

(defn get-entries
  "Get TimeCamp entries within a date range.  Date formats must be yyyy-MM-dd"
  [api-token user-id from to]
  (let [url (str/join "/" [(tc-get-url "entries" api-token) "from" from "to" to "user_ids" user-id])
        response (client/get url)]
    (json/read-str (:body response))))

(s/defn localdatetime-to-tc-date :- String [localdatetime :- LocalDateTime]
  (.format localdatetime DateTimeFormatter/ISO_LOCAL_DATE))

(s/defn localdatetime-to-tc-time :- String [localdatetime :- LocalDateTime]
  (.format localdatetime DateTimeFormatter/ISO_LOCAL_TIME))

(s/defn canonical-event-to-tc-event [{:keys [start-time end-time description
                                             source source-id task-type]} :- CanonicalEvent]
  {:date (localdatetime-to-tc-date start-time)
   :start_time (localdatetime-to-tc-time start-time)
   :end_time (localdatetime-to-tc-time end-time)
   ;; timecamp couldn't deal with the quotes in json, use yaml to avoid quotes
   :note (yaml/generate-string {:description description :source source :source-id source-id}
                               :dumper-options {:flow-style :block})
   :task_id (name (get TASK_IDS task-type task-type))
   ;; TimeCamp docs say :duration is required, it isn't.
   ;; It weirdly allows an event to tracked as some other amount of time and
   ;; doesn't appear to be editable via the portal, meaning this program could
   ;; create an error the user couldn't easily find or fix.
   })

(defn post-tc-entry [api-token data]
  (client/post
   (tc-post-url "entries" api-token)
   {:form-params data :content-type :x-www-form-urlencoded :throw-exceptions false}))

(s/defn summary-post-event-to-tc [tc-api-token :- String event :- CanonicalEvent]
  (let [tc-event (canonical-event-to-tc-event event)
        {:keys [status body] :as response} (post-tc-entry tc-api-token tc-event)]
    {:ok (>= 300 status)
     :status status
     :body (json/read-str body)
     :tc-event tc-event}))
