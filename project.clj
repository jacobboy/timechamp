(defproject timecop "0.1.0-SNAPSHOT"
  :description "Populate Timecamp from your Google calendar"
  ;; :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.3.0"]
                 [org.clojure/data.json "0.2.6"]
                 [com.google.api-client/google-api-client "1.22.0"]
                 [com.google.oauth-client/google-oauth-client-jetty "1.22.0"]
                 [com.google.apis/google-api-services-calendar "v3-rev243-1.22.0"]
                 [clj-yaml "0.4.0"]])
