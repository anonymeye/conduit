(ns conduit.providers.mock
  "Mock provider for testing.
  
  Provides configurable mock responses for testing without making real API calls.
  Supports both synchronous and streaming responses, tool calls, and error simulation."
  (:require [conduit.core :as c]
            [conduit.error :as error]
            [conduit.util.json :as json]
            [clojure.core.async :as a]
            [clojure.string :as str]))

;; Mock Response Builders

(defn text-response
  "Create a simple text response.
  
  Arguments:
    text - Response text
    opts - Optional map with:
           :model - Model name (default: \"mock-model\")
           :usage - Usage map with :input-tokens, :output-tokens
           :stop-reason - Stop reason (default: :end-turn)
  
  Returns:
    Response map
  
  Example:
    (text-response \"Hello!\" {:usage {:input-tokens 10 :output-tokens 5}})"
  [text & [opts]]
  (merge {:id "mock-response-id"
          :role :assistant
          :content text
          :model (or (:model opts) "mock-model")
          :stop-reason (or (:stop-reason opts) :end-turn)
          :usage (or (:usage opts)
                     {:input-tokens 10
                      :output-tokens 20
                      :total-tokens 30})}
         (dissoc opts :model :usage :stop-reason)))

(defn tool-response
  "Create a response with tool calls.
  
  Arguments:
    tool-calls - Vector of tool call maps with :id, :name, :arguments
    opts - Optional map (same as text-response)
  
  Returns:
    Response map with tool calls
  
  Example:
    (tool-response [{:id \"call_1\" 
                     :name \"get_weather\" 
                     :arguments {:location \"NYC\"}}])"
  [tool-calls & [opts]]
  (merge {:id "mock-response-id"
          :role :assistant
          :content ""
          :model (or (:model opts) "mock-model")
          :stop-reason (or (:stop-reason opts) :tool-use)
          :tool-calls (mapv (fn [idx tc]
                              {:id (or (:id tc) (str "mock-call-" idx))
                               :type :function
                               :function {:name (:name tc)
                                          :arguments (:arguments tc)}})
                            (range)
                            tool-calls)
          :usage (or (:usage opts)
                     {:input-tokens 10
                      :output-tokens 20
                      :total-tokens 30})}
         (dissoc opts :model :usage :stop-reason)))

(defn error-response
  "Create an error response.
  
  Arguments:
    error-type - Error type keyword (e.g., :rate-limit, :authentication)
    message - Error message
    opts - Optional additional error data
  
  Returns:
    Error map
  
  Example:
    (error-response :rate-limit \"Too many requests\")"
  [error-type message & [opts]]
  (case error-type
    :rate-limit (error/rate-limit-error message (merge {:provider :mock} opts))
    :authentication (error/authentication-error :mock)
    :authorization (error/authorization-error :mock message)
    :invalid-request (error/invalid-request-error message (merge {:provider :mock} opts))
    :validation (error/validation-error message (or (:errors opts) []))
    (ex-info message {:type error-type :provider :mock})))

;; Streaming Helpers

(defn- text->stream-events
  "Convert text into streaming events.
  
  Splits text into chunks and creates content-delta events."
  [text chunk-size]
  (let [chunks (if (< (count text) chunk-size)
                 [text]
                 (map str/join (partition-all chunk-size text)))]
    (concat
      [{:type :message-start
        :message {:id "mock-stream-id"
                  :model "mock-model"
                  :role :assistant}}]
      (map-indexed (fn [_idx chunk]
                     {:type :content-delta
                      :index 0
                      :delta {:text chunk}})
                   chunks)
      [{:type :message-end
        :stop-reason :end-turn
        :usage {:input-tokens 10
                :output-tokens (count text)
                :total-tokens (+ 10 (count text))}}])))

(defn- tool-calls->stream-events
  "Convert tool calls into streaming events."
  [tool-calls]
  (concat
    [{:type :message-start
      :message {:id "mock-stream-id"
                :model "mock-model"
                :role :assistant}}]
    (mapcat (fn [tc]
              (let [args-json (json/encode (get-in tc [:function :arguments]))]
                [{:type :tool-use-start
                  :index 0
                  :tool-call {:id (:id tc)
                              :name (get-in tc [:function :name])}}
                 {:type :tool-use-delta
                  :index 0
                  :delta {:partial-json args-json}}
                 {:type :tool-use-stop
                  :index 0}]))
            tool-calls)
    [{:type :message-end
      :stop-reason :tool-use
      :usage {:input-tokens 10
              :output-tokens 50
              :total-tokens 60}}]))

;; Mock Model Implementation

(defrecord MockModel [config]
  c/ChatModel
  
  (chat [this messages]
    (c/chat this messages {}))
  
  (chat [_ messages opts]
    (let [{:keys [response delay-ms error]} config]
      ;; Simulate delay if specified
      (when delay-ms
        (Thread/sleep delay-ms))
      
      ;; Return error if configured
      (when error
        (throw (if (instance? Exception error)
                 error
                 (error-response (:type error) (:message error) (dissoc error :type :message)))))
      
      ;; Return configured response or generate one
      (cond
        (fn? response)
        (response messages opts)
        
        response
        response
        
        ;; Default: echo last user message
        :else
        (let [last-user-msg (->> messages
                                 (filter #(= :user (:role %)))
                                 last)]
          (text-response (str "Echo: " (:content last-user-msg)))))))
  
  (stream [this messages]
    (c/stream this messages {}))
  
  (stream [_ messages opts]
    (let [ch (a/chan 100)
          {:keys [response delay-ms error chunk-size]} config
          chunk-size (or chunk-size 5)]
      (a/thread
        (try
          ;; Simulate delay if specified
          (when delay-ms
            (Thread/sleep delay-ms))
          
          ;; Return error if configured
          (when error
            (a/>!! ch {:type :error
                       :error (if (map? error)
                                error
                                {:type :error
                                 :message (str error)})})
            (throw (ex-info "Mock error" {})))
          
          ;; Generate stream events
          (let [events (cond
                         (fn? response)
                         (let [resp (response messages opts)]
                           (if (:tool-calls resp)
                             (tool-calls->stream-events (:tool-calls resp))
                             (text->stream-events (:content resp) chunk-size)))
                         
                         response
                         (if (:tool-calls response)
                           (tool-calls->stream-events (:tool-calls response))
                           (text->stream-events (:content response) chunk-size))
                         
                         ;; Default: echo last user message
                         :else
                         (let [last-user-msg (->> messages
                                                  (filter #(= :user (:role %)))
                                                  last)
                               text (str "Echo: " (:content last-user-msg))]
                           (text->stream-events text chunk-size)))]
            
            ;; Emit events with optional delay between chunks
            (doseq [event events]
              (a/>!! ch event)
              (when (and delay-ms (= :content-delta (:type event)))
                (Thread/sleep (quot delay-ms 10)))))
          
          (catch Exception _e
            ;; Error already sent, just exit
            nil)
          (finally
            (a/close! ch))))
      ch))
  
  (model-info [_]
    {:provider :mock
     :model "mock-model"
     :capabilities {:streaming true
                    :tool-calling true
                    :vision false
                    :json-mode false
                    :structured-output false}})
  
  c/Wrappable
  (wrap [this middleware]
    (c/->WrappedModel this middleware)))

;; Public API

(defn model
  "Create a mock model instance.
  
  Options:
    :response - Fixed response to return (Response map or fn [messages opts] -> Response)
    :delay-ms - Delay in milliseconds before responding (simulates latency)
    :error - Error to throw (ExceptionInfo or error map with :type and :message)
    :chunk-size - Size of text chunks for streaming (default: 5)
  
  Returns:
    MockModel instance implementing ChatModel protocol
  
  Examples:
    ;; Simple text response
    (model {:response (text-response \"Hello!\")})
    
    ;; Dynamic response based on input
    (model {:response (fn [messages opts]
                        (text-response (str \"You said: \" 
                                           (:content (last messages)))))})
    
    ;; Simulate rate limiting
    (model {:error (error-response :rate-limit \"Too many requests\")})
    
    ;; Simulate slow response
    (model {:response (text-response \"Slow response\")
            :delay-ms 2000})"
  [opts]
  (->MockModel opts))

;; Preset Mock Models

(defn echo-model
  "Create a mock model that echoes user input.
  
  Arguments:
    opts - Optional configuration (delay-ms, chunk-size)
  
  Example:
    (def echo (echo-model {:delay-ms 100}))"
  [& [opts]]
  (model (merge opts
                {:response (fn [messages _]
                             (let [last-user (->> messages
                                                  (filter #(= :user (:role %)))
                                                  last)]
                               (text-response (str "Echo: " (:content last-user)))))})))

(defn counting-model
  "Create a mock model that counts messages.
  
  Returns responses like 'This is message 1', 'This is message 2', etc.
  
  Arguments:
    opts - Optional configuration (delay-ms, chunk-size)
  
  Example:
    (def counter (counting-model))"
  [& [opts]]
  (let [counter (atom 0)]
    (model (merge opts
                  {:response (fn [_ _]
                               (text-response (str "This is message " 
                                                   (swap! counter inc))))}))))

(defn tool-calling-model
  "Create a mock model that always returns a specific tool call.
  
  Arguments:
    tool-name - Name of the tool to call
    arguments - Arguments map for the tool
    opts - Optional configuration (delay-ms)
  
  Example:
    (def weather-model 
      (tool-calling-model \"get_weather\" {:location \"NYC\"}))"
  [tool-name arguments & [opts]]
  (model (merge opts
                {:response (tool-response [{:id "mock-tool-call-1"
                                            :name tool-name
                                            :arguments arguments}])})))

(defn error-model
  "Create a mock model that always throws an error.
  
  Arguments:
    error-type - Error type keyword
    message - Error message
    opts - Optional configuration (delay-ms)
  
  Example:
    (def rate-limited (error-model :rate-limit \"Too many requests\"))"
  [error-type message & [opts]]
  (model (merge opts
                {:error {:type error-type
                         :message message}})))

(defn slow-model
  "Create a mock model with simulated latency.
  
  Arguments:
    delay-ms - Delay in milliseconds
    response - Optional response (defaults to echo)
  
  Example:
    (def slow (slow-model 5000))"
  [delay-ms & [response]]
  (model {:delay-ms delay-ms
          :response response}))

