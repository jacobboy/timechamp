(ns timecop.tc-util
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def TC_URL_TEMPLATE "https://www.timecamp.com/third_party/api/%s/format/json/api_token/%s")

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

(defn get-entries [api-token user-id from to]
  (let [url (str/join "/" [(tc-get-url "entries" api-token) "from" from "to" to "user_ids" user-id])
        response (client/get url)]
    (json/read-str (:body response))))

(defn post-tc [api-token data]
  (client/post
   (tc-post-url "entries" api-token)
   {:form-params data :content-type :x-www-form-urlencoded}))
