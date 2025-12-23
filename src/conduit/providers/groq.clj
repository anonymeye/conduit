(ns conduit.providers.groq
  "Groq provider implementation.
  
  Groq provides ultra-fast inference with an OpenAI-compatible API.
  Supports streaming, tool calling, and JSON mode."
  (:require [conduit.core :as c]
            [conduit.schema :as schema]
            [conduit.error :as error]
            [conduit.util.http :as http]
            [conduit.util.json :as json]
            [clojure.core.async :as a]
            [clojure.string :as str]))

;; Configuration

(def default-config
  "Default configuration for Groq API."
  {:base-url "https://api.groq.com/openai/v1"
   :timeout-ms 60000})

(def models
  "Available Groq models with their specifications."
  {:llama-3-3-70b {:id "llama-3.3-70b-versatile"
                   :context-window 131072
                   :max-output 32768}
   :llama-3-1-70b {:id "llama-3.1-70b-versatile"
                   :context-window 131072
                   :max-output 32768}
   :llama-3-1-8b {:id "llama-3.1-8b-instant"
                  :context-window 131072
                  :max-output 8192}
   :llama-3-2-1b {:id "llama-3.2-1b-preview"
                  :context-window 131072
                  :max-output 8192}
   :llama-3-2-3b {:id "llama-3.2-3b-preview"
                  :context-window 131072
                  :max-output 8192}
   :mixtral-8x7b {:id "mixtral-8x7b-32768"
                  :context-window 32768
                  :max-output 32768}
   :gemma-2-9b {:id "gemma2-9b-it"
                :context-window 8192
                :max-output 8192}})

(def capabilities
  "Capabilities supported by Groq models."
  {:streaming true
   :tool-calling true
   :vision false
   :json-mode true
   :structured-output false
   :max-context 131072})

;; Request Building

(defn- build-content-block
  "Convert a Conduit content block to Groq format."
  [{:keys [type text source]}]
  (case type
    :text {:type "text" :text text}
    :image {:type "image_url" 
            :image_url {:url (if (= :url (:type source))
                               (:url source)
                               (str "data:" (:media-type source) 
                                    ";base64," (:data source)))}}))

(defn- build-messages
  "Convert Conduit messages to Groq format."
  [messages]
  (mapv (fn [{:keys [role content tool-call-id tool-calls]}]
          (cond-> {:role (name role)
                   :content (if (string? content)
                              content
                              (if (vector? content)
                                (mapv build-content-block content)
                                content))}
            tool-call-id (assoc :tool_call_id tool-call-id)
            tool-calls (assoc :tool_calls 
                              (mapv (fn [{:keys [id type function]}]
                                      {:id id
                                       :type (name type)
                                       :function {:name (:name function)
                                                  :arguments (json/encode (:arguments function))}})
                                    tool-calls))))
        messages))

(defn- build-tool-choice
  "Convert Conduit tool-choice to Groq format."
  [tool-choice]
  (cond
    (keyword? tool-choice) (name tool-choice)
    (map? tool-choice) {:type "function"
                        :function {:name (:name tool-choice)}}
    :else tool-choice))

(defn- build-tools
  "Convert Conduit tools to Groq format."
  [tools]
  (when (seq tools)
    (mapv (fn [{:keys [name description schema parameters]}]
            {:type "function"
             :function {:name name
                        :description description
                        :parameters (or parameters
                                        (schema/malli->json-schema schema))}})
          tools)))

(defn- build-request
  "Build a Groq API request from Conduit format."
  [config messages opts]
  (cond-> {:model (:model config)
           :messages (build-messages messages)}
    (:temperature opts) (assoc :temperature (:temperature opts))
    (:max-tokens opts) (assoc :max_tokens (:max-tokens opts))
    (:top-p opts) (assoc :top_p (:top-p opts))
    (:stop opts) (assoc :stop (:stop opts))
    (:tools opts) (assoc :tools (build-tools (:tools opts)))
    (:tool-choice opts) (assoc :tool_choice (build-tool-choice (:tool-choice opts)))
    (= :json (:response-format opts)) (assoc :response_format {:type "json_object"})
    (:stream opts) (assoc :stream true)))

;; Response Parsing

(defn- parse-stop-reason
  "Convert Groq finish reason to Conduit stop reason."
  [finish-reason]
  (case finish-reason
    "stop" :end-turn
    "tool_calls" :tool-use
    "length" :max-tokens
    "content_filter" :content-filter
    :end-turn))

(defn- parse-tool-calls
  "Convert Groq tool calls to Conduit format."
  [tool-calls]
  (when (seq tool-calls)
    (mapv (fn [{:keys [id function]}]
            {:id id
             :type :function
             :function {:name (:name function)
                        :arguments (json/decode (:arguments function) true)}})
          tool-calls)))

(defn- parse-response
  "Parse a Groq API response to Conduit format."
  [response]
  (let [choice (first (:choices response))
        message (:message choice)]
    (cond-> {:id (:id response)
             :role :assistant
             :content (or (:content message) "")
             :model (:model response)
             :stop-reason (parse-stop-reason (:finish_reason choice))}
      (:usage response)
      (assoc :usage {:input-tokens (get-in response [:usage :prompt_tokens])
                     :output-tokens (get-in response [:usage :completion_tokens])
                     :total-tokens (get-in response [:usage :total_tokens])})
      (:tool_calls message)
      (assoc :tool-calls (parse-tool-calls (:tool_calls message))))))

;; Streaming

(defn- parse-stream-event
  "Parse a streaming event from Groq SSE format."
  [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try
          (let [parsed (json/decode data true)
                choice (first (:choices parsed))
                delta (:delta choice)]
            (cond
              ;; Content delta
              (:content delta)
              {:type :content-delta
               :index (or (:index choice) 0)
               :delta {:text (:content delta)}}
              
              ;; Tool call start
              (and (:tool_calls delta) 
                   (-> delta :tool_calls first :function :name))
              (let [tc (first (:tool_calls delta))]
                {:type :tool-use-start
                 :index (or (:index tc) 0)
                 :tool-call {:id (:id tc)
                             :name (-> tc :function :name)}})
              
              ;; Tool call delta (arguments streaming)
              (and (:tool_calls delta) 
                   (-> delta :tool_calls first :function :arguments))
              (let [tc (first (:tool_calls delta))]
                {:type :tool-use-delta
                 :index (or (:index tc) 0)
                 :delta {:partial-json (-> tc :function :arguments)}})
              
              ;; Finish reason present
              (:finish_reason choice)
              (cond-> {:type :message-end
                       :stop-reason (parse-stop-reason (:finish_reason choice))}
                (:usage parsed)
                (assoc :usage {:input-tokens (get-in parsed [:usage :prompt_tokens])
                               :output-tokens (get-in parsed [:usage :completion_tokens])
                               :total-tokens (get-in parsed [:usage :total_tokens])}))))
          (catch Exception e
            {:type :error
             :error {:type :parse-error
                     :message (str "Failed to parse stream event: " (.getMessage e))}}))))))

;; Model Implementation

(defrecord GroqModel [config connection-manager]
  c/ChatModel
  
  (chat [this messages]
    (c/chat this messages {}))
  
  (chat [_ messages opts]
    (let [url (str (:base-url config) "/chat/completions")
          request (build-request config messages opts)
          headers {"Authorization" (str "Bearer " (:api-key config))
                   "Content-Type" "application/json"}]
      (try
        (let [response (http/post url 
                                  (json/encode request)
                                  {:headers headers
                                   :timeout-ms (:timeout-ms config)
                                   :connection-manager connection-manager})]
          (if (http/success? response)
            (parse-response (http/json-body response))
            (let [status (:status response)]
              (cond
                (= status 401) (throw (error/authentication-error :groq))
                (= status 429) (throw (error/rate-limit-error 
                                        "Rate limit exceeded"
                                        {:provider :groq}))
                (>= status 500) (throw (error/server-error 
                                         status 
                                         (:body response) 
                                         :groq))
                :else (throw (error/invalid-request-error 
                               (str "Groq API error: " status)
                               {:status status
                                :body (:body response)
                                :provider :groq}))))))
        (catch clojure.lang.ExceptionInfo e
          (if (error/conduit-error? e)
            (throw e)
            (throw (error/invalid-request-error
                     (str "Groq API request failed: " (.getMessage e))
                     {:provider :groq
                      :cause e}))))
        (catch Exception e
          (throw (error/network-error
                   (str "Network error: " (.getMessage e))
                   e))))))
  
  (stream [this messages]
    (c/stream this messages {}))
  
  (stream [_ messages opts]
    (let [ch (a/chan 100)
          url (str (:base-url config) "/chat/completions")
          request (build-request config messages (assoc opts :stream true))
          headers {"Authorization" (str "Bearer " (:api-key config))
                   "Content-Type" "application/json"
                   "Accept" "text/event-stream"}]
      (a/thread
        (try
          (http/stream-sse
            url
            {:method :post
             :headers headers
             :body (json/encode request)
             :timeout (:timeout-ms config)
             :connection-manager connection-manager}
            (fn [line]
              (when-let [event (parse-stream-event line)]
                (a/>!! ch event))))
          (catch clojure.lang.ExceptionInfo e
            (a/>!! ch {:type :error 
                       :error (if (error/conduit-error? e)
                                (ex-data e)
                                {:type :server-error
                                 :message (.getMessage e)})}))
          (catch Exception e
            (a/>!! ch {:type :error 
                       :error {:type :network
                               :message (.getMessage e)}}))
          (finally
            (a/close! ch))))
      ch))
  
  (model-info [_]
    {:provider :groq
     :model (:model config)
     :capabilities capabilities
     :context-window (get-in models [(keyword (:model config)) :context-window] 131072)
     :max-output (get-in models [(keyword (:model config)) :max-output] 32768)})
  
  c/Wrappable
  (wrap [this middleware]
    (c/->WrappedModel this middleware)))

;; Public API

(defn model
  "Create a Groq model instance.
  
  The API key can be provided via the :api-key option or the GROQ_API_KEY
  environment variable.
  
  Options:
    :model   - Model ID (default: \"llama-3.3-70b-versatile\")
             Available: \"llama-3.3-70b-versatile\", \"llama-3.1-70b-versatile\",
                       \"llama-3.1-8b-instant\", \"mixtral-8x7b-32768\", etc.
    :api-key - Groq API key (or GROQ_API_KEY env var)
    :base-url - API base URL (default: \"https://api.groq.com/openai/v1\")
    :timeout-ms - Request timeout in milliseconds (default: 60000)
  
  Returns:
    GroqModel instance implementing ChatModel protocol
  
  Example:
    (def groq (model {:model \"llama-3.1-8b-instant\"
                      :api-key \"gsk-...\"}))"
  [opts]
  (let [config (merge default-config
                      {:model "llama-3.3-70b-versatile"
                       :api-key (or (:api-key opts) 
                                    (System/getenv "GROQ_API_KEY"))}
                      opts)
        conn-mgr (http/create-connection-manager 
                   {:timeout 10
                    :threads 4
                    :default-per-route 2})]
    (when-not (:api-key config)
      (throw (ex-info "Groq API key required. Set :api-key option or GROQ_API_KEY environment variable."
                      {:type :authentication
                       :provider :groq})))
    (->GroqModel config conn-mgr)))

(defn shutdown
  "Shutdown a Groq model instance and release resources.
  
  Closes the HTTP connection manager and releases any pooled connections.
  
  Arguments:
    model - GroqModel instance
  
  Example:
    (shutdown groq-model)"
  [^GroqModel model]
  (when-let [cm (:connection-manager model)]
    (http/shutdown-connection-manager cm)))

