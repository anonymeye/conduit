(ns conduit.error
  "Error types and handling utilities for Conduit.")

;; Error Types

(def error-types
  "All error types in Conduit"
  #{:rate-limit        ; 429 - Too many requests
    :authentication    ; 401 - Invalid API key
    :authorization     ; 403 - Not allowed
    :invalid-request   ; 400 - Bad request format
    :not-found         ; 404 - Model/resource not found
    :server-error      ; 5xx - Provider server error
    :timeout           ; Request timed out
    :network           ; Connection failed
    :validation        ; Response validation failed
    :tool-error        ; Tool execution failed
    :max-iterations    ; Agent loop exceeded max iterations
    :max-tokens        ; Response exceeded token limit
    :content-filter})  ; Content blocked by safety filter

;; Error Creation

(defn error
  "Create a Conduit error.
  
  Arguments:
    type    - Error type keyword (must be in error-types)
    message - Human-readable message
    data    - Additional error data (optional)
  
  Returns:
    ExceptionInfo with structured data"
  ([type message]
   (error type message {}))
  ([type message data]
   (when-not (contains? error-types type)
     (throw (IllegalArgumentException. (str "Invalid error type: " type))))
   (ex-info message
            (merge {:type type
                    :message message
                    :timestamp (System/currentTimeMillis)}
                   data))))

(defn rate-limit-error
  "Create rate limit error with retry info.
  
  Arguments:
    message - Error message
    opts    - Map with :retry-after (seconds) and :provider"
  [message {:keys [retry-after provider]}]
  (error :rate-limit message
         (cond-> {:provider provider}
           retry-after (assoc :retry-after retry-after))))

(defn authentication-error
  "Create authentication error.
  
  Arguments:
    provider - Provider keyword"
  [provider]
  (error :authentication
         (str "Invalid API key for " (name provider))
         {:provider provider}))

(defn authorization-error
  "Create authorization error.
  
  Arguments:
    provider - Provider keyword
    message  - Optional custom message"
  ([provider]
   (authorization-error provider "Access denied"))
  ([provider message]
   (error :authorization message {:provider provider})))

(defn invalid-request-error
  "Create invalid request error.
  
  Arguments:
    message - Error message
    details - Optional details map"
  ([message]
   (invalid-request-error message {}))
  ([message details]
   (error :invalid-request message details)))

(defn server-error
  "Create server error.
  
  Arguments:
    status   - HTTP status code
    body     - Response body
    provider - Provider keyword"
  [status body provider]
  (error :server-error
         (str "Server error from " (name provider))
         {:status status
          :body body
          :provider provider}))

(defn timeout-error
  "Create timeout error.
  
  Arguments:
    message  - Error message
    provider - Provider keyword
    timeout  - Timeout value in ms"
  [message provider timeout]
  (error :timeout message
         {:provider provider
          :timeout-ms timeout}))

(defn network-error
  "Create network error.
  
  Arguments:
    message - Error message
    cause   - Original exception"
  [message cause]
  (error :network message
         {:cause cause
          :cause-message (.getMessage cause)}))

(defn validation-error
  "Create validation error.
  
  Arguments:
    message - Error message
    errors  - Validation errors (from Malli)"
  [message errors]
  (error :validation message
         {:errors errors}))

(defn tool-error
  "Create tool execution error.
  
  Arguments:
    tool-name - Name of the tool
    message   - Error message
    cause     - Optional original exception"
  ([tool-name message]
   (tool-error tool-name message nil))
  ([tool-name message cause]
   (error :tool-error message
          (cond-> {:tool-name tool-name}
            cause (assoc :cause cause
                        :cause-message (.getMessage cause))))))

;; Error Predicates

(defn error-type
  "Get error type from exception.
  
  Arguments:
    e - ExceptionInfo
  
  Returns:
    Error type keyword or nil"
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:type (ex-data e))))

(defn conduit-error?
  "Check if exception is a Conduit error.
  
  Arguments:
    e - Exception
  
  Returns:
    true if e is a Conduit error"
  [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (contains? error-types (error-type e))))

(defn rate-limited?
  "Check if error is rate limit.
  
  Arguments:
    e - Exception
  
  Returns:
    true if error is rate limit"
  [e]
  (= :rate-limit (error-type e)))

(defn authentication-error?
  "Check if error is authentication failure.
  
  Arguments:
    e - Exception
  
  Returns:
    true if error is authentication failure"
  [e]
  (= :authentication (error-type e)))

(defn authorization-error?
  "Check if error is authorization failure.
  
  Arguments:
    e - Exception
  
  Returns:
    true if error is authorization failure"
  [e]
  (= :authorization (error-type e)))

(defn retryable?
  "Check if error is worth retrying.
  
  Arguments:
    e - Exception
  
  Returns:
    true if error should be retried"
  [e]
  (contains? #{:rate-limit :server-error :timeout :network}
             (error-type e)))

(defn client-error?
  "Check if error is client's fault (don't retry).
  
  Arguments:
    e - Exception
  
  Returns:
    true if error is client's fault"
  [e]
  (contains? #{:authentication :authorization :invalid-request :validation}
             (error-type e)))

;; HTTP Status Mapping

(defn status->error-type
  "Map HTTP status to error type.
  
  Arguments:
    status - HTTP status code
  
  Returns:
    Error type keyword"
  [status]
  (cond
    (= status 400) :invalid-request
    (= status 401) :authentication
    (= status 403) :authorization
    (= status 404) :not-found
    (= status 429) :rate-limit
    (<= 500 status 599) :server-error
    :else :invalid-request))

(defn http-error
  "Create error from HTTP response.
  
  Arguments:
    response - HTTP response map with :status, :body, :headers
    provider - Provider keyword
  
  Returns:
    ExceptionInfo with error details"
  [response provider]
  (let [status (:status response)
        type (status->error-type status)
        body (if (string? (:body response))
               (:body response)
               (str (:body response)))
        retry-after (some-> (:headers response)
                           (get "retry-after")
                           (get "Retry-After")
                           parse-long)]
    (error type
           (str "HTTP " status " from " (name provider))
           (cond-> {:status status
                    :body body
                    :provider provider
                    :headers (:headers response)}
             retry-after (assoc :retry-after retry-after)))))

;; Error Handling Utilities

(defn with-error-handler
  "Wrap operation with error handler.
  
  Arguments:
    f        - Function to wrap
    handlers - Map of error-type to handler function
  
  Returns:
    Wrapped function that catches and handles errors
  
  Example:
    (def safe-chat
      (with-error-handler
        #(c/chat model %1 %2)
        {:rate-limit (fn [e args] {:error \"Rate limited\"})
         :authentication (fn [e args] {:error \"Invalid API key\"})}))"
  [f handlers]
  (fn [& args]
    (try
      (apply f args)
      (catch clojure.lang.ExceptionInfo e
        (if-let [handler (get handlers (error-type e))]
          (handler e args)
          (throw e))))))

(defn try-call
  "Call function returning result map instead of throwing.
  
  Arguments:
    f    - Function to call
    args - Arguments to pass to f
  
  Returns:
    {:ok result} or {:error error-data}
  
  Example:
    (let [result (try-call c/chat model messages {})]
      (if (:ok result)
        (handle-response (:ok result))
        (handle-error (:error result))))"
  [f & args]
  (try
    {:ok (apply f args)}
    (catch Exception e
      {:error (if (conduit-error? e)
                (ex-data e)
                {:type :unknown
                 :message (.getMessage e)
                 :cause e})})))

