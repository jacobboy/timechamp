(ns timechamp.tc-util
  (:require [clj-http.client :as client]
            [clj-yaml.core :as yaml]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [schema.core :as s]
            [timechamp.schema :refer :all])
  (:import java.time.format.DateTimeFormatter
           java.time.LocalDateTime
           timechamp.schema.CanonicalEvent))

(def ^:const TC_URL_TEMPLATE
  "https://www.timecamp.com/third_party/api/%s/format/json/api_token/%s")

(def ^:const TASK_IDS {:meeting "9238867"})

(def TCDate s/Str)

(def TCTime s/Str)

(def TCEvent
  {:date TCDate :start_time TCTime :end_time TCTime :note s/Str :task_id s/Str})

(defn ^:private tc-get-url [endpoint api-token]
  (format TC_URL_TEMPLATE endpoint api-token))

(defn ^:private tc-post-url [endpoint api-token]
  (format TC_URL_TEMPLATE endpoint api-token))

(defn ^:private get-users [api-token]
  (let [url (tc-get-url "users" api-token)
        response (client/get url)]
    (json/read-str (:body response))))

(defn get-user-id-from-email [api-token email]
  (-> (get-users api-token)
      (->> (filter #(= email (% "email"))))
      first
      (get "user_id")))

(defn ^:private parent-task
  "Return the parent task, or nil if no parent."
  [task task-id->task]
  (-> task
      :parent_id
      task-id->task))

(defn ^:private parent->tasks-from-task-id->task
  [task-id->task]
  (letfn [(drop-parent [parent->tasks] (dissoc parent->tasks nil))
          (compare-by-name [taskl taskr] (compare (:name taskl) (:name taskr)))]
    (->> task-id->task
         vals
         (group-by #(parent-task % task-id->task))
         drop-parent
         (fmap (partial sort compare-by-name))
         (into (sorted-map-by compare-by-name)))))

(defn ^:private max-child-name-len [parent->tasks]
  (->> parent->tasks
       vals
       flatten
       (map :name)
       (map count)
       (apply max)))

(defn ^:private format-parent->tasks [parent->tasks]
  (let [child-len (max-child-name-len parent->tasks)
        child-format-str (str "    %-" child-len "s    %s")
        format-child #(format child-format-str (:name %) (:task_id %))

        parent-len (+ 4 child-len)
        parent-format-str (str "%-" parent-len "s    %s")
        format-parent #(format parent-format-str (:name %) (:task_id %))

        format-parent-children (fn [[parent children]]
                                 (->> children
                                      (map format-child)
                                      (cons (format-parent parent))))]
    (->> parent->tasks
         (mapcat format-parent-children)
         (str/join \newline))))

(defn list-tasks
  "Calls TimeCamp's tasks API endpoint and parses the response, returning a map
  containing keys :ok? and :message. On success, :ok? is true, and :message is a
  string containing tasks and task ids formatted for print. On failure, :ok? is
  false and :message contains the error provided by TimeCamp."
  [api-token]
  (let [tasks-url (tc-get-url "tasks" api-token)
        {:keys [body status]} (client/get tasks-url {:throw-exceptions false})
        ok? (< status 300)]
    (if ok?
      {:message (->> body
                     json/read-str
                     (fmap keywordize-keys)
                     parent->tasks-from-task-id->task
                     format-parent->tasks)
       :ok? true}
      {:message body :ok? false})))

(defn get-entries
  "Get TimeCamp entries within a date range. Date formats must be yyyy-MM-dd."
  [api-token user-id from-date to-date]
  (let [url (str/join "/"
                      [(tc-get-url "entries" api-token)
                       "from" from-date
                       "to" to-date
                       "user_ids" user-id])
        response (client/get url)]
    (json/read-str (:body response))))

(s/defn ^:private localdatetime-to-tc-date :- TCDate
  [localdatetime :- LocalDateTime]
  (.format localdatetime DateTimeFormatter/ISO_LOCAL_DATE))

(s/defn ^:private localdatetime-to-tc-time :- TCDate
  [localdatetime :- LocalDateTime]
  (.format localdatetime DateTimeFormatter/ISO_LOCAL_TIME))

(s/defn ^:private canonical-event-to-tc-event :- TCEvent
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

(defn ^:private post-tc-entry [api-token data]
  (client/post
   (tc-post-url "entries" api-token)
   {:form-params data
    :content-type :x-www-form-urlencoded
    :throw-exceptions false}))

(s/defn summary-post-event-to-tc
  :- {:ok? s/Bool :status s/Num :body {s/Any s/Any} :tc-event TCEvent}
  "Format the event for TimeCamp and post. Returns a summary of the response
  containing
  :ok?      - was the post successful?
  :status   - response status
  :body     - response body
  :tc-event - the TimeCamp object derived from the provided event"
  [tc-api-token :- String event :- CanonicalEvent]
  (let [tc-event (canonical-event-to-tc-event event)
        {:keys [status body] :as response}
          (post-tc-entry tc-api-token tc-event)]
    {:ok? (< status 300)
     :status status
     :body (json/read-str body)
     :tc-event tc-event}))
