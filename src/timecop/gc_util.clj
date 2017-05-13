(ns timecop.gc-util
  (:require [clojure.java.io :as io])
  (:import (com.google.api.client.extensions.java6.auth.oauth2 AuthorizationCodeInstalledApp)
           (com.google.api.client.extensions.jetty.auth.oauth2 LocalServerReceiver)
           (com.google.api.client.googleapis.auth.oauth2 GoogleCredential
                                                         GoogleClientSecrets
                                                         GoogleAuthorizationCodeFlow
                                                         GoogleAuthorizationCodeFlow$Builder)
           (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
           (com.google.api.client.json.jackson2 JacksonFactory)
           (com.google.api.client.util.store FileDataStoreFactory)
           (com.google.api.client.util DateTime)
           (com.google.api.services.calendar Calendar
                                             Calendar$Builder
                                             CalendarScopes)))

(def APP_NAME "timecop")

(defn get-oauth-creds [scopes secrets-loc data-store-dir-loc]
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

(defn get-calendar-service [secrets-loc data-store-dir]
  (let [scopes [CalendarScopes/CALENDAR_READONLY]
        cred (get-oauth-creds scopes secrets-loc data-store-dir)
        http-transport (GoogleNetHttpTransport/newTrustedTransport)
        json-factory (JacksonFactory/getDefaultInstance)
        ;; dataStoreFile (io/file data-store-dir)
        ;; dataStoreFactory (FileDataStoreFactory. dataStoreFile)
        ]
    (->
     (Calendar$Builder. http-transport json-factory cred)
     (.setApplicationName APP_NAME)
     .build)))

(defn get-events [calList maxResults timeMin secrets-loc data-store-dir]
  (let [service (get-calendar-service secrets-loc data-store-dir)]
    (->
     service
     .events
     (.list calList)
     (.setMaxResults maxResults)
     (.setTimeMin timeMin)
     (.setOrderBy "startTime")
     (.setSingleEvents true)
     (.execute)
     (.getItems)
     (remove #(.isDateOnly %)))))
