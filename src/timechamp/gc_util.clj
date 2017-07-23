(ns timechamp.gc-util
  (:require [clojure.java.io :as io]
            [schema.core :as s]
            [timechamp.schema :refer [canonical-event]])
  (:import
   com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
   com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
   [com.google.api.client.googleapis.auth.oauth2 GoogleAuthorizationCodeFlow
    GoogleClientSecrets
    GoogleAuthorizationCodeFlow$Builder]
   com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
   com.google.api.client.json.jackson2.JacksonFactory
   com.google.api.client.util.DateTime
   com.google.api.client.util.store.FileDataStoreFactory
   [com.google.api.services.calendar Calendar Calendar$Builder CalendarScopes]
   [com.google.api.services.calendar.model Event EventDateTime]
   [java.time Instant LocalDateTime ZoneId]
   java.time.format.DateTimeFormatter
   timechamp.schema.CanonicalEvent))

(def ^:const APP_NAME "timechamp")
(def ^:const SOURCE_GC :gc)

(defn ^:private get-oauth-creds [scopes secrets-loc data-store-dir-loc]
  (let [stream (io/input-stream secrets-loc)
        stream-reader (io/reader stream)
        http-transport (GoogleNetHttpTransport/newTrustedTransport)
        json-factory (JacksonFactory/getDefaultInstance)
        data-store-dir (io/file data-store-dir-loc)
        data-store-factory (FileDataStoreFactory. data-store-dir)
        client-secrets (GoogleClientSecrets/load json-factory stream-reader)
        flow-builder (GoogleAuthorizationCodeFlow$Builder. http-transport
                                                           json-factory
                                                           client-secrets
                                                           scopes)
        flow (-> flow-builder
                 (.setDataStoreFactory data-store-factory)
                 (.setAccessType "offline")
                 .build)
        authed-app (AuthorizationCodeInstalledApp. flow (LocalServerReceiver.))
        credential (.authorize authed-app "user")]
    (println (format "Cred saved to %s" data-store-dir-loc))
    credential))

(defn ^:private get-calendar-service ^Calendar
  [secrets-loc data-store-dir]
  (let [scopes [CalendarScopes/CALENDAR_READONLY]
        cred (get-oauth-creds scopes secrets-loc data-store-dir)
        http-transport (GoogleNetHttpTransport/newTrustedTransport)
        json-factory (JacksonFactory/getDefaultInstance)]
    (->
     (Calendar$Builder. http-transport json-factory cred)
     (.setApplicationName APP_NAME)
     .build)))

(defn ^:private timezone-for-calendar ^ZoneId
  [^Calendar calendar-service ^String calendar-id]
  (-> calendar-service
      .calendars
      (.get calendar-id)
      .execute
      .getTimeZone
      ZoneId/of))

(defn ^:private localdatetime-to-gc-datetime ^DateTime
  [^LocalDateTime localdatetime ^ZoneId zone]
  (let [zoneddatetime (.atZone localdatetime zone)]
    (DateTime. (.format zoneddatetime DateTimeFormatter/ISO_OFFSET_DATE_TIME))))

(defn ^:private gc-datetime-to-localdatetime ^LocalDateTime [^DateTime datetime]
  (-> (Instant/ofEpochMilli (.getValue datetime))
      (.atZone (ZoneId/of (subs (.toStringRfc3339 datetime) 23)))
      (.toLocalDateTime)))

(s/defn ^:private gc-event-to-canonical-event :- CanonicalEvent
  [gc-event]
  (let [start (.. gc-event getStart getDateTime)
        end (.. gc-event getEnd getDateTime)]
    (canonical-event {:start-time (gc-datetime-to-localdatetime start)
                      :end-time (gc-datetime-to-localdatetime end)
                      :description (. gc-event getSummary)
                      :source SOURCE_GC
                      :source-id (. gc-event getId)
                      :task-type :meeting})))

(s/defn get-events :- [CanonicalEvent]
  "Retrieve events from Google Calendar.
  Arguments:
    secrets-loc    Path to the secrets file for Google OAuth.
    data-store-dir Path to the directory in which to store OAuth creds.
    calendar-id    ID of the calendar to pull events from.
    time-min       Beginning day and time.
    time-max       Ending day and time."
  [secrets-loc :- String
   data-store-dir :- String
   calendar-id :- String
   time-min :- LocalDateTime
   time-max :- LocalDateTime]
  (let [service (get-calendar-service secrets-loc data-store-dir)
        zone (timezone-for-calendar service calendar-id)]
    (->
     service
     .events
     (.list calendar-id)
     (.setSingleEvents true)
     (.setTimeMin (localdatetime-to-gc-datetime time-min zone))
     (.setTimeMax  (localdatetime-to-gc-datetime time-max zone))
     .execute
     .getItems
     (->> (map gc-event-to-canonical-event)))))
