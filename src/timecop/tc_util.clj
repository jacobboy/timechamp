(ns timecop.tc-util
  (:require [clj-http.client :as client]
            [clj-yaml.core :as yaml]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [schema.core :as s])
  (:import java.time.format.DateTimeFormatter
           java.time.LocalDateTime
           timecop.schema.CanonicalEvent))

(def ^:const TC_URL_TEMPLATE
  "https://www.timecamp.com/third_party/api/%s/format/json/api_token/%s")

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

(defn ^:private parent-task
  "Return the parent task, or nil if no parent."
  [task task-id->task]
  (-> task
      :parent_id
      task-id->task
      #_:name))

(defn ^:private format-parent->tasks
  [parent tasks]
  (let [task-formatter #(str "    " (:name %) " - " (:task_id %))
        formatted-tasks (map task-formatter tasks)
        parent-line (str (:name parent) " - " (:task_id parent))
        all-lines (cons parent-line formatted-tasks)]
    (str/join \newline all-lines)))

(defn ^:private format-task-id->task
  [task-id->task]
  (let [task-id->task (fmap keywordize-keys task-id->task)
        tasks (vals task-id->task)
        parent->tasks (group-by #(parent-task % task-id->task) tasks)
        parent->children (dissoc parent->tasks nil)
        name-comparator #(compare (:name %1) (:name %2))
        sorted-parent->children (into
                                 (sorted-map-by name-comparator)
                                 parent->children)]
    (str/join
     \newline
     (map
      #(apply format-parent->tasks %)
      sorted-parent->children))))

(defn list-tasks
  [api-token]
  (let [tasks-url (tc-get-url "tasks" api-token)
        resp (client/get tasks-url)
        ok? (< (:status resp) 300)
        body (json/read-str (:body resp))]
    (if ok?
      {:message (format-task-id->task body) :ok? true}
      {:message body :ok? false})))

(defn get-entries
  "Get TimeCamp entries within a date range.  Date formats must be yyyy-MM-dd"
  [api-token user-id from to]
  (let [url (str/join "/"
                      [(tc-get-url "entries" api-token)
                       "from" from
                       "to" to
                       "user_ids" user-id])
        response (client/get url)]
    (json/read-str (:body response))))

(s/defn localdatetime-to-tc-date :- String
  [localdatetime :- LocalDateTime]
  (.format localdatetime DateTimeFormatter/ISO_LOCAL_DATE))

(s/defn localdatetime-to-tc-time :- String
  [localdatetime :- LocalDateTime]
  (.format localdatetime DateTimeFormatter/ISO_LOCAL_TIME))

(s/defn canonical-event-to-tc-event
  [{:keys [start-time end-time description
           source source-id task-type]} :- CanonicalEvent]
  {:date (localdatetime-to-tc-date start-time)
   :start_time (localdatetime-to-tc-time start-time)
   :end_time (localdatetime-to-tc-time end-time)
   :note (yaml/generate-string
          {:description description :source source :source-id source-id}
          :dumper-options {:flow-style :block})
   :task_id (name (get TASK_IDS task-type task-type))
   ;; TimeCamp docs say :duration is required, it isn't.
   ;; duration allows an event to tracked as some other amount of time and
   ;; doesn't appear to be editable via the portal, meaning an error in this
   ;; program could create a tracking issue the user couldn't easily find or
   ;; fix.
   })

(defn post-tc-entry [api-token data]
  (client/post
   (tc-post-url "entries" api-token)
   {:form-params data :content-type :x-www-form-urlencoded :throw-exceptions false}))

(s/defn summary-post-event-to-tc
  [tc-api-token :- String event :- CanonicalEvent]
  (let [tc-event (canonical-event-to-tc-event event)
        {:keys [status body] :as response}
          (post-tc-entry tc-api-token tc-event)]
    {:ok (< status 300)
     :status status
     :body (json/read-str body)
     :tc-event tc-event}))
