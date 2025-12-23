(ns conduit.util.http
  "HTTP client wrapper using clj-http."
  (:require [clj-http.client :as http]
            [clj-http.conn-mgr :as conn-mgr]
            [conduit.util.json :as json]
            [conduit.error :as error])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net SocketTimeoutException ConnectException]))

;; Connection Manager

(def ^:private default-connection-manager
  "Default connection manager with pooling"
  (delay (conn-mgr/make-reusable-conn-manager
          {:timeout 10
           :threads 4
           :default-per-route 2})))

;; Request Helpers

(defn- build-headers
  "Build headers map with defaults.
  
  Arguments:
    headers - User-provided headers
    opts    - Options map
  
  Returns:
    Complete headers map"
  [headers opts]
  (merge {"Content-Type" "application/json"
          "Accept" "application/json"
          "User-Agent" "Conduit/0.1.0"}
         headers
         (when-let [api-key (:api-key opts)]
           {"Authorization" (str "Bearer " api-key)})))

(defn- build-request
  "Build complete request map.
  
  Arguments:
    url     - Request URL
    options - Request options
  
  Returns:
    clj-http request map"
  [url options]
  (let [{:keys [method body headers timeout-ms connection-manager
                query-params throw-exceptions]
         :or {method :get
              timeout-ms 60000
              throw-exceptions false}} options]
    (cond-> {:url url
             :method method
             :headers (build-headers headers options)
             :connection-manager (or connection-manager @default-connection-manager)
             :socket-timeout timeout-ms
             :connection-timeout timeout-ms
             :throw-exceptions throw-exceptions
             :as :auto}
      body (assoc :body (if (string? body) body (json/encode body)))
      query-params (assoc :query-params query-params))))

;; Error Handling

(defn- handle-exception
  "Convert HTTP exceptions to Conduit errors.
  
  Arguments:
    e        - Exception
    provider - Provider keyword
  
  Throws:
    ExceptionInfo with Conduit error"
  [e provider]
  (cond
    ;; HTTP error response
    (instance? clojure.lang.ExceptionInfo e)
    (let [data (ex-data e)]
      (if (:status data)
        (throw (error/http-error data provider))
        (throw e)))
    
    ;; Timeout
    (instance? SocketTimeoutException e)
    (throw (error/timeout-error
            "Request timed out"
            provider
            nil))
    
    ;; Connection error
    (instance? ConnectException e)
    (throw (error/network-error
            "Connection failed"
            e))
    
    ;; Unknown error
    :else
    (throw (error/network-error
            (str "HTTP request failed: " (.getMessage e))
            e))))

;; Request Functions

(defn request
  "Make HTTP request.
  
  Arguments:
    url     - Request URL
    options - Options map with:
              :method - HTTP method (default :get)
              :body - Request body (map or string)
              :headers - Additional headers
              :query-params - Query parameters
              :timeout-ms - Timeout in milliseconds (default 60000)
              :api-key - API key for Authorization header
              :provider - Provider keyword (for error handling)
  
  Returns:
    Response map with :status, :headers, :body
  
  Throws:
    ExceptionInfo with Conduit error"
  [url options]
  (let [provider (:provider options :unknown)
        req (build-request url options)]
    (try
      (let [response (http/request req)]
        ;; Check for error status codes
        (if (and (>= (:status response) 400)
                 (not (:throw-exceptions options)))
          (throw (error/http-error response provider))
          response))
      (catch Exception e
        (handle-exception e provider)))))

(defn get
  "Make GET request.
  
  Arguments:
    url     - Request URL
    options - Options map (see request)
  
  Returns:
    Response map"
  [url options]
  (request url (assoc options :method :get)))

(defn post
  "Make POST request.
  
  Arguments:
    url     - Request URL
    body    - Request body (map or string)
    options - Options map (see request)
  
  Returns:
    Response map"
  [url body options]
  (request url (assoc options :method :post :body body)))

(defn put
  "Make PUT request.
  
  Arguments:
    url     - Request URL
    body    - Request body (map or string)
    options - Options map (see request)
  
  Returns:
    Response map"
  [url body options]
  (request url (assoc options :method :put :body body)))

(defn delete
  "Make DELETE request.
  
  Arguments:
    url     - Request URL
    options - Options map (see request)
  
  Returns:
    Response map"
  [url options]
  (request url (assoc options :method :delete)))

;; Streaming Support

(defn stream-lines
  "Stream response line by line.
  
  Arguments:
    url       - Request URL
    options   - Options map (see request)
    line-fn   - Function called for each line
  
  The line-fn receives each line as a string.
  
  Throws:
    ExceptionInfo with Conduit error"
  [url options line-fn]
  (let [provider (:provider options :unknown)
        req (build-request url (assoc options :as :stream))]
    (try
      (let [response (http/request req)
            reader (BufferedReader. (InputStreamReader. (:body response)))]
        (try
          (loop []
            (when-let [line (.readLine reader)]
              (line-fn line)
              (recur)))
          (finally
            (.close reader))))
      (catch Exception e
        (handle-exception e provider)))))

(defn stream-sse
  "Stream Server-Sent Events (SSE).
  
  Arguments:
    url       - Request URL
    options   - Options map (see request)
    event-fn  - Function called for each event
  
  The event-fn receives a map with :event, :data, :id keys.
  
  Throws:
    ExceptionInfo with Conduit error"
  [url options event-fn]
  (let [current-event (atom {:event nil :data [] :id nil})]
    (stream-lines
     url
     (assoc-in options [:headers "Accept"] "text/event-stream")
     (fn [line]
       (cond
         ;; Empty line = event boundary
         (clojure.string/blank? line)
         (when (seq (:data @current-event))
           (event-fn (update @current-event :data
                            #(clojure.string/join "\n" %)))
           (reset! current-event {:event nil :data [] :id nil}))
         
         ;; Comment line
         (clojure.string/starts-with? line ":")
         nil
         
         ;; Event field
         (clojure.string/starts-with? line "event:")
         (swap! current-event assoc :event
                (clojure.string/trim (subs line 6)))
         
         ;; Data field
         (clojure.string/starts-with? line "data:")
         (swap! current-event update :data conj
                (clojure.string/trim (subs line 5)))
         
         ;; ID field
         (clojure.string/starts-with? line "id:")
         (swap! current-event assoc :id
                (clojure.string/trim (subs line 3))))))))

;; Response Helpers

(defn json-body
  "Extract and parse JSON body from response.
  
  Arguments:
    response - HTTP response map
  
  Returns:
    Parsed JSON as Clojure data"
  [response]
  (when-let [body (:body response)]
    (if (string? body)
      (json/decode body)
      body)))

(defn success?
  "Check if response is successful (2xx status).
  
  Arguments:
    response - HTTP response map
  
  Returns:
    true if status is 2xx"
  [response]
  (<= 200 (:status response) 299))

;; Retry Helpers

(defn with-retry
  "Execute HTTP request with retry logic.
  
  Arguments:
    request-fn - Function that makes the request
    options    - Options map with:
                 :max-retries - Maximum retry attempts (default 3)
                 :initial-delay-ms - Initial delay in ms (default 1000)
                 :max-delay-ms - Maximum delay in ms (default 60000)
                 :backoff-multiplier - Backoff multiplier (default 2.0)
  
  Returns:
    Response from successful request
  
  Throws:
    ExceptionInfo if all retries exhausted"
  [request-fn {:keys [max-retries initial-delay-ms max-delay-ms backoff-multiplier]
               :or {max-retries 3
                    initial-delay-ms 1000
                    max-delay-ms 60000
                    backoff-multiplier 2.0}}]
  (loop [attempt 0
         delay-ms initial-delay-ms]
    (let [result (try
                   {:ok (request-fn)}
                   (catch clojure.lang.ExceptionInfo e
                     {:error e}))]
      (if (:ok result)
        (:ok result)
        (let [e (:error result)]
          (if (and (< attempt max-retries)
                   (error/retryable? e))
            (let [retry-after (or (get-in (ex-data e) [:retry-after])
                                 (quot delay-ms 1000))
                  wait-ms (min (* retry-after 1000) max-delay-ms)]
              (Thread/sleep wait-ms)
              (recur (inc attempt)
                     (min (* delay-ms backoff-multiplier) max-delay-ms)))
            (throw e)))))))

;; Connection Management

(defn create-connection-manager
  "Create a custom connection manager.
  
  Arguments:
    options - Options map with:
              :timeout - Connection timeout in seconds
              :threads - Thread pool size
              :default-per-route - Connections per route
  
  Returns:
    Connection manager"
  [options]
  (conn-mgr/make-reusable-conn-manager options))

(defn shutdown-connection-manager
  "Shutdown a connection manager.
  
  Arguments:
    cm - Connection manager"
  [cm]
  (conn-mgr/shutdown-manager cm))

