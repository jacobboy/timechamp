(ns timechamp.jiraoauth
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [yaml.core :as yaml])
  (:import [com.google.api.client.auth.oauth
            OAuthAuthorizeTemporaryTokenUrl
            OAuthGetAccessToken
            OAuthGetTemporaryToken
            OAuthParameters
            OAuthRsaSigner]
           com.google.api.client.http.GenericUrl
           com.google.api.client.http.javanet.NetHttpTransport
           com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64
           [java.awt Desktop Desktop$Action]
           java.io.IOException
           java.net.URI
           java.security.KeyFactory
           java.security.spec.PKCS8EncodedKeySpec
           java.util.Scanner))

(defprotocol IOAuthTokenFactory
  "A Factory protocol for an object that builds OAuthGetAccessToken and
  OAuthGetTemporaryToken objects."
  (get-temp-token [this consumer-key private-key])
  (get-access-token [this consumer-key private-key verifier temp-token])
  (get-auth-url [this temp-token])
  (get-auth-filepath [this]))

(defn ^:private get-private-key [private-key]
  (->> private-key
       (Base64/decodeBase64)
       (PKCS8EncodedKeySpec.)
       (.generatePrivate (KeyFactory/getInstance "RSA"))))

(defn get-rsa-signer [private-key]
  (let [signer (OAuthRsaSigner.)]
    (set! (.privateKey signer) (get-private-key private-key))
    signer))

(defn ^:private open-in-browser [url]
  (try
    (if (and
         (Desktop/isDesktopSupported)
         (.isSupported (Desktop/getDesktop) Desktop$Action/BROWSE))
      (.browse (Desktop/getDesktop) (URI/create url))
      (println "Could not open browser."))
    (catch IOException e
      (println "Could not open browser."))
    (catch InternalError e
      ;; A bug in a JRE can cause Desktop.isDesktopSupported() to throw an
      ;; InternalError rather than returning false. The error reads, "Can't
      ;; connect to X11 window server using ':0.0' as the value of the DISPLAY
      ;; variable." The exact error message may vary slightly.
      (println "Could not open browser."))))

(defn ^:private verify [auth-url]
  (println
   (str "Go to " auth-url " to authorize and receive your verification code. "
        "Attempting to open in browser."))
  (open-in-browser auth-url)
  (println "Enter verification code:")
  (read-line))

(defn auth [consumer-key private-key auth-factory]
  (let [auth-filepath (io/file (get-auth-filepath auth-factory))
        authed (.exists auth-filepath)]
    (if authed
      (-> auth-filepath
          slurp
          yaml/parse-string
          (select-keys [:token :verifier]))
      (let [temp-token (.execute
                        (get-temp-token auth-factory consumer-key private-key))
            verifier (verify (get-auth-url auth-factory temp-token))
            access-token (->>
                          (get-access-token auth-factory consumer-key
                                            private-key verifier temp-token)
                          .execute
                          .token)
            token-and-verifier {:token access-token :verifier verifier}]
        (spit
         auth-filepath
         (yaml/generate-string
          token-and-verifier
          :dumper-options
          {:flow-style :block}))

        token-and-verifier))))

(defn get-oauth-params [consumer-key private-key token verifier]
  (let [params (OAuthParameters.)]
    (set! (.consumerKey params) consumer-key)
    (set! (.signer params) (get-rsa-signer private-key))
    (set! (.token params) token)
    (set! (.verifier params) verifier)
    params))

(defn make-request
  [get-url
   consumer-key
   private-key
   auth-factory]
  (let [{:keys [token verifier]} (auth consumer-key private-key auth-factory)
        params (get-oauth-params consumer-key private-key token verifier)
        scanner (-> (NetHttpTransport.)
                    (.createRequestFactory params)
                    (.buildGetRequest (GenericUrl. get-url))
                    .execute
                    .getContent
                    Scanner.
                    (.useDelimiter "\\A"))]
    (when (.hasNext scanner) (json/read-str (.next scanner) :key-fn keyword))))
