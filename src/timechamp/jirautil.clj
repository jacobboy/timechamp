(ns timechamp.jirautil
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [timechamp.config :as config]
            [timechamp.jiraoauth :as oauth]
            [timechamp.util :refer [expand-home]])
  (:import com.google.api.client.auth.oauth.OAuthAuthorizeTemporaryTokenUrl
           com.google.api.client.http.apache.ApacheHttpTransport
           java.net.URLEncoder))

(def ^:private JIRA_API_PATH "rest/api/2/")
(def ^:private AGILE_API_PATH "rest/agile/1.0/")

(gen-class
 :name timechamp.jirautil.GetTempToken
 :extends com.google.api.client.auth.oauth.OAuthGetTemporaryToken
 :exposes {usePost {:set setUsePost}})

(gen-class
 :name timechamp.jirautil.GetAccessToken
 :extends com.google.api.client.auth.oauth.OAuthGetAccessToken
 :exposes {usePost {:set setUsePost}})

(defn ^:private jira-hostname-and-private-key []
  (config/get-config-values :jira :hostname :private-key))

(defn ^:private board-and-status-name []
  (config/get-config-values :jira :board-name :status-name))

(defn ^:private url [hostname filename]
  (let [hostname (if (str/ends-with? hostname "/")
                   (subs hostname 0 (- (count hostname) 1))
                   hostname)
        protocol-idx (str/index-of hostname "//")
        hostname (if (some? protocol-idx)
                   (subs hostname (+ protocol-idx 2) (count hostname))
                   hostname)
        filename (if (str/starts-with? filename "/")
                   (subs filename 1 (count filename))
                   filename)]
    (str "https://" hostname "/" filename)))

(defn ^:private jira-url [hostname api-path filename query-params]
  (let [protocol-idx (str/index-of hostname "//")
        hostname (if (some? protocol-idx)
                   (subs hostname (+ protocol-idx 2) (count hostname))
                   hostname)
        hostname (if (str/ends-with? hostname "/")
                   (subs hostname 0 (- (count hostname) 1))
                   hostname)
        filename (if (str/starts-with? filename "/")
                   (subs filename 1 (count filename))
                   filename)]
    (str "https://" hostname
         "/" api-path filename
         #_(when (seq query-params) (str "?" (URLEncoder/encode query-params)))
         (when (seq query-params) (str "?" query-params)))))

(defn ^:private jira-temp-token [jira-hostname consumer-key private-key]
  (let [req-token-url (url jira-hostname "/plugins/servlet/oauth/request-token")
        temp-token (timechamp.jirautil.GetTempToken. req-token-url)]
    (set! (.consumerKey temp-token) consumer-key)
    (set! (.signer temp-token) (oauth/get-rsa-signer private-key))
    (set! (.transport temp-token) (ApacheHttpTransport.))
    (set! (.callback temp-token) "oob")
    (.setUsePost temp-token true)
    temp-token))

(defn ^:private jira-access-token
  [jira-hostname consumer-key private-key verifier temp-token]
  (let [acc-token-url (url jira-hostname "/plugins/servlet/oauth/access-token")
        access-token (timechamp.jirautil.GetAccessToken. acc-token-url)]
    (set! (.consumerKey access-token) consumer-key)
    (set! (.signer access-token) (oauth/get-rsa-signer private-key))
    (set! (.transport access-token) (ApacheHttpTransport.))
    (set! (.verifier access-token) verifier)
    (set! (.temporaryToken access-token) (.token temp-token))
    (.setUsePost access-token true)
    access-token))

(defn ^:private jira-auth-url [jira-hostname temp-token]
  (let [url (url jira-hostname "/plugins/servlet/oauth/authorize")
        auth-url (OAuthAuthorizeTemporaryTokenUrl. url)]
    (set! (.temporaryToken auth-url) (.token temp-token))
    (str auth-url)))

(defn ^:private jira-auth-filepath [data-store-dir]
  (.getCanonicalPath  ;; easiest way to do path joins?
   (io/file
    (expand-home data-store-dir)
    "jira.yml")))

(defrecord ^:private JiraOAuthTokenFactory [jira-hostname data-store-dir]
  oauth/IOAuthTokenFactory
  (get-temp-token [_ consumer-key private-key]
    (jira-temp-token jira-hostname consumer-key private-key))

  (get-access-token [_ consumer-key private-key verifier temp-token]
    (jira-access-token jira-hostname
                       consumer-key
                       private-key
                       verifier
                       temp-token))

  (get-auth-url [_ temp-token]
    (jira-auth-url jira-hostname temp-token))

  (get-auth-filepath [_]
    (jira-auth-filepath data-store-dir)))

(defn ^:private make-request
  [api-path jira-filename query-params data-store-dir]
  (let [consumer-key "OauthKey"
        [hostname private-key] (jira-hostname-and-private-key)
        get-url (jira-url hostname api-path jira-filename query-params)
        auth-factory (->JiraOAuthTokenFactory hostname data-store-dir)]
    (oauth/make-request get-url
                        consumer-key
                        private-key
                        auth-factory)))

(defn ^:private get-id [response]
  (-> response
      (:values)
      first
      :id))

(defn ^:private get-board-id [board-name]
  (get-id (make-request AGILE_API_PATH
                        "board"
                        (str "name=" (URLEncoder/encode board-name))
                        "~/.timechamp/data")))

(defn ^:private get-current-sprint-id [board-id]
  (get-id (make-request AGILE_API_PATH
                        (str "board/" board-id "/sprint")
                        "state=active"
                        "~/.timechamp/data")))

(defn ^:private get-user-name []
  (:name (make-request JIRA_API_PATH "myself" nil "~/.timechamp/data")))

(defn ^:private get-tracking-tc-ids []
  (let [[board-name status-name] (board-and-status-name)
        board-id (get-board-id board-name)
        sprint-id (get-current-sprint-id board-id)
        user-name (get-user-name)
        jira-filename (str "board/" board-id "/sprint/" sprint-id "/issue")
        query-params (str "fields=customfield_12101&jql="
                          (URLEncoder/encode
                           (str "status = \"" status-name "\" AND assignee IN (" user-name ")")))
        [id-map] (config/get-config-values :jira :id-map)]

    (->> (make-request
          AGILE_API_PATH
          jira-filename
          query-params
          "~/.timechamp/data")

         :issues

         (map #(get-in % [:fields :customfield_12101 :value]))
         (set)
         (map keyword)
         (map id-map)
         ;; flatten
         (filter some?))))

(defn is-jira-id
  [task-id]
  (= "jira" task-id))

(defn expand-jira-tuple [[task-id task-time :as tuple]]
  (if (is-jira-id task-id)
    (let [jira-tc-ids (get-tracking-tc-ids)
          task-count (count jira-tc-ids)
          new-task-time (when (pos? task-count) (double (/ task-time task-count)))
          new-jira-tuples (map #(vector % new-task-time) jira-tc-ids)]
      ;; TODO should I throw an error instead?
      (println (str "No Jira tasks, omitting from day. "
                    "If you definitely have tasks tracked, check they match "
                    "your id map in the config."))
      new-jira-tuples)
    [tuple]))
