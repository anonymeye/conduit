(ns conduit.core
  "Core protocols and API for Conduit."
  (:require [conduit.schema :as schema]
            [conduit.interceptor :as interceptor]))

;; Protocols

(defprotocol ChatModel
  "Protocol for chat-based language models."
  
  (chat 
    [model messages]
    [model messages opts]
    "Send messages to the model and receive a response.
    
    Arguments:
      model    - The model instance
      messages - Vector of message maps (see schema/Message)
      opts     - Optional map of ChatOptions (see schema/ChatOptions)
    
    Returns:
      Response map with :role, :content, :usage, :stop-reason, etc.
      (see schema/Response)
    
    Throws:
      ExceptionInfo with :type for categorized errors")
  
  (stream
    [model messages]
    [model messages opts]
    "Send messages and receive streaming response.
    
    Arguments:
      model    - The model instance
      messages - Vector of message maps
      opts     - Optional map of ChatOptions
    
    Returns:
      core.async channel yielding StreamEvent maps.
      Channel closes when stream completes or errors.
    
    The channel will emit events in order:
      1. :message-start (once)
      2. :content-delta (multiple)
      3. :tool-use-start/:tool-use-delta (if tools used)
      4. :message-end (once, includes usage)
    
    On error, emits :error event then closes.")
  
  (model-info
    [model]
    "Return information about this model instance.
    
    Returns:
      Map with :provider, :model, :capabilities"))

(defprotocol Embeddable
  "Protocol for embedding models."
  
  (embed
    [model texts]
    [model texts opts]
    "Generate embeddings for texts.
    
    Arguments:
      model - The embedding model instance
      texts - String or vector of strings
      opts  - Optional map with :dimensions, :task-type, etc.
    
    Returns:
      Map with :embeddings (vector of float vectors) and :usage"))

(defprotocol Wrappable
  "Protocol for models that can be wrapped with handler functions.
  
  This protocol allows wrapping models with custom handler functions for
  advanced use cases. For most cross-cutting concerns, use interceptors
  instead (see conduit.interceptors and conduit.core/chat-with-interceptors)."
  
  (wrap
    [model handler-fn]
    "Wrap model with a handler function.
    
    Handler function signature: (fn [handler] (fn [model messages opts] ...))
    
    Returns:
      New model instance with handler applied"))

;; Wrapped Model Implementation

(defrecord WrappedModel [inner handler]
  ChatModel
  
  (chat [this messages]
    (handler inner messages {}))
  
  (chat [this messages opts]
    (handler inner messages opts))
  
  (stream [this messages]
    ;; Streaming typically bypasses wrapped handlers
    (stream inner messages {}))
  
  (stream [this messages opts]
    (stream inner messages opts))
  
  (model-info [this]
    (model-info inner))
  
  Wrappable
  
  (wrap [this handler-fn]
    (->WrappedModel this (handler-fn handler))))

;; Default Wrappable Implementation

(extend-protocol Wrappable
  ;; Default implementation for any Object (ChatModel)
  Object
  (wrap [model handler-fn]
    (->WrappedModel model (handler-fn (fn [m msgs opts] (chat m msgs opts))))))

;; Utility Functions

(defn supports?
  "Check if model supports a capability.
  
  Arguments:
    model      - Model instance
    capability - Capability keyword (e.g., :streaming, :tool-calling, :vision)
  
  Returns:
    true if model supports the capability, false otherwise
  
  Example:
    (supports? model :streaming)
    => true"
  [model capability]
  (get-in (model-info model) [:capabilities capability] false))

(defn provider
  "Get the provider keyword for a model.
  
  Arguments:
    model - Model instance
  
  Returns:
    Provider keyword (e.g., :grok, :anthropic, :openai)
  
  Example:
    (provider model)
    => :grok"
  [model]
  (:provider (model-info model)))

(defn model-name
  "Get the model name string.
  
  Arguments:
    model - Model instance
  
  Returns:
    Model name string (e.g., \"grok-3\", \"claude-3-opus\")
  
  Example:
    (model-name model)
    => \"grok-3\""
  [model]
  (:model (model-info model)))

(defn capabilities
  "Get all capabilities for a model.
  
  Arguments:
    model - Model instance
  
  Returns:
    Capabilities map (see schema/Capabilities)
  
  Example:
    (capabilities model)
    => {:streaming true
        :tool-calling true
        :vision false
        :json-mode true
        :max-context 131072}"
  [model]
  (:capabilities (model-info model)))

;; Message Builders

(defn system-message
  "Create a system message.
  
  Arguments:
    content - Message content (string)
  
  Returns:
    Message map
  
  Example:
    (system-message \"You are a helpful assistant.\")
    => {:role :system :content \"You are a helpful assistant.\"}"
  [content]
  {:role :system :content content})

(defn user-message
  "Create a user message.
  
  Arguments:
    content - Message content (string or vector of content blocks)
  
  Returns:
    Message map
  
  Example:
    (user-message \"Hello!\")
    => {:role :user :content \"Hello!\"}"
  [content]
  {:role :user :content content})

(defn assistant-message
  "Create an assistant message.
  
  Arguments:
    content    - Message content (string or vector of content blocks)
    tool-calls - Optional vector of tool calls
  
  Returns:
    Message map
  
  Example:
    (assistant-message \"Hello there!\")
    => {:role :assistant :content \"Hello there!\"}"
  ([content]
   {:role :assistant :content content})
  ([content tool-calls]
   {:role :assistant :content content :tool-calls tool-calls}))

(defn tool-message
  "Create a tool result message.
  
  Arguments:
    tool-call-id - ID of the tool call this is responding to
    content      - Tool result content
  
  Returns:
    Message map
  
  Example:
    (tool-message \"call_123\" \"The weather is sunny.\")
    => {:role :tool :tool-call-id \"call_123\" :content \"The weather is sunny.\"}"
  [tool-call-id content]
  {:role :tool :tool-call-id tool-call-id :content content})

;; Content Builders

(defn text-block
  "Create a text content block.
  
  Arguments:
    text - Text content
  
  Returns:
    Text block map
  
  Example:
    (text-block \"Hello!\")
    => {:type :text :text \"Hello!\"}"
  [text]
  {:type :text :text text})

(defn image-block
  "Create an image content block.
  
  Arguments:
    source - Image source map with :type, :data/:url, :media-type
  
  Returns:
    Image block map
  
  Example:
    (image-block {:type :url :url \"https://example.com/image.jpg\"})
    => {:type :image :source {:type :url :url \"https://example.com/image.jpg\"}}"
  [source]
  {:type :image :source source})

(defn image-url
  "Create an image source from URL.
  
  Arguments:
    url        - Image URL
    media-type - Optional media type (e.g., \"image/jpeg\")
  
  Returns:
    Image source map
  
  Example:
    (image-url \"https://example.com/image.jpg\" \"image/jpeg\")
    => {:type :url :url \"https://example.com/image.jpg\" :media-type \"image/jpeg\"}"
  ([url]
   {:type :url :url url})
  ([url media-type]
   {:type :url :url url :media-type media-type}))

(defn image-base64
  "Create an image source from base64 data.
  
  Arguments:
    data       - Base64 encoded image data
    media-type - Media type (e.g., \"image/jpeg\")
  
  Returns:
    Image source map
  
  Example:
    (image-base64 \"iVBORw0KGgo...\" \"image/png\")
    => {:type :base64 :data \"iVBORw0KGgo...\" :media-type \"image/png\"}"
  [data media-type]
  {:type :base64 :data data :media-type media-type})

;; Validation Helpers

(defn validate-messages
  "Validate messages against schema.
  
  Arguments:
    messages - Vector of message maps
  
  Returns:
    messages if valid
  
  Throws:
    ExceptionInfo with :type :validation if invalid"
  [messages]
  (schema/validate [:vector schema/Message] messages))

(defn validate-response
  "Validate response against schema.
  
  Arguments:
    response - Response map
  
  Returns:
    response if valid
  
  Throws:
    ExceptionInfo with :type :validation if invalid"
  [response]
  (schema/validate schema/Response response))

(defn validate-options
  "Validate chat options against schema.
  
  Arguments:
    opts - Options map
  
  Returns:
    opts if valid
  
  Throws:
    ExceptionInfo with :type :validation if invalid"
  [opts]
  (schema/validate schema/ChatOptions opts))

;; Convenience Functions

(defn simple-chat
  "Simple chat with a single user message.
  
  Arguments:
    model   - Model instance
    message - User message string
    opts    - Optional chat options
  
  Returns:
    Response map
  
  Example:
    (simple-chat model \"What is 2+2?\")
    => {:role :assistant :content \"4\" ...}"
  ([model message]
   (simple-chat model message {}))
  ([model message opts]
   (chat model [(user-message message)] opts)))

(defn extract-content
  "Extract content string from response.
  
  Arguments:
    response - Response map
  
  Returns:
    Content string (or first text block if content is vector)
  
  Example:
    (extract-content response)
    => \"The answer is 4.\""
  [response]
  (let [content (:content response)]
    (if (string? content)
      content
      (if (vector? content)
        (or (->> content
                 (filter #(= :text (:type %)))
                 first
                 :text)
            "")
        (str content)))))

(defn extract-tool-calls
  "Extract tool calls from response.
  
  Arguments:
    response - Response map
  
  Returns:
    Vector of tool call maps or empty vector
  
  Example:
    (extract-tool-calls response)
    => [{:id \"call_123\" :type :function :function {:name \"get_weather\" ...}}]"
  [response]
  (or (:tool-calls response) []))

(defn has-tool-calls?
  "Check if response has tool calls.
  
  Arguments:
    response - Response map
  
  Returns:
    true if response has tool calls
  
  Example:
    (has-tool-calls? response)
    => true"
  [response]
  (boolean (seq (extract-tool-calls response))))

;; Token Usage Helpers

(defn total-tokens
  "Get total tokens from response.
  
  Arguments:
    response - Response map
  
  Returns:
    Total token count
  
  Example:
    (total-tokens response)
    => 150"
  [response]
  (let [usage (:usage response)]
    (or (:total-tokens usage)
        (+ (:input-tokens usage 0)
           (:output-tokens usage 0)))))

(defn input-tokens
  "Get input tokens from response.
  
  Arguments:
    response - Response map
  
  Returns:
    Input token count
  
  Example:
    (input-tokens response)
    => 100"
  [response]
  (get-in response [:usage :input-tokens] 0))

(defn output-tokens
  "Get output tokens from response.
  
  Arguments:
    response - Response map
  
  Returns:
    Output token count
  
  Example:
    (output-tokens response)
    => 50"
  [response]
  (get-in response [:usage :output-tokens] 0))

;; Interceptor Integration

(defn chat-with-interceptors
  "Execute chat with interceptor chain.
  
  Interceptors provide a data-driven way to transform requests and responses,
  handle errors, implement caching, logging, and more. Inspired by Pedestal's
  interceptor pattern.
  
  Arguments:
    model        - ChatModel instance
    messages     - Vector of message maps
    interceptors - Vector of interceptor maps (from conduit.interceptor)
    opts         - Optional chat options map
  
  Returns:
    Response map (same as chat protocol method)
  
  The execution flow:
    1. Create context with model, messages, opts
    2. Execute interceptor :enter functions (forward through queue)
    3. Call model with (possibly transformed) messages and opts
    4. Add response to context
    5. Execute interceptor :leave functions (backward through stack)
    6. Return final response
  
  Interceptors can:
    - Transform :messages via :transformed-messages in context
    - Transform :opts via :transformed-opts in context
    - Transform :response in the leave phase
    - Handle errors via :error function
    - Terminate early via (interceptor/terminate context)
  
  Example:
    (require '[conduit.interceptor :as int])
    
    (def logging-int
      (int/interceptor
        {:name :logging
         :enter (fn [ctx]
                  (println \"Calling model with\" (count (:messages ctx)) \"messages\")
                  ctx)
         :leave (fn [ctx]
                  (println \"Response tokens:\" (total-tokens (:response ctx)))
                  ctx)}))
    
    (chat-with-interceptors model messages [logging-int] {:temperature 0.7})
  
  See also:
    - conduit.interceptor namespace for interceptor creation
    - chat-with-interceptors-ctx for returning full context"
  ([model messages interceptors]
   (chat-with-interceptors model messages interceptors {}))
  
  ([model messages interceptors opts]
   ;; 1. Build initial context
   (let [initial-ctx (interceptor/context
                      {:model model
                       :messages messages
                       :opts opts
                       :queue interceptors})
         
         ;; 2. Execute enter phase
         ctx-after-enter (interceptor/execute initial-ctx)]
     
     ;; Check for early termination or errors after enter phase
     (if (:error ctx-after-enter)
       ;; If there's an error after enter phase (and error handlers), throw it
       (throw (:error ctx-after-enter))
       
      ;; 3. Call model (outside interceptor chain)
      (let [final-messages (or (:transformed-messages ctx-after-enter)
                               (:messages ctx-after-enter))
            final-opts (or (:transformed-opts ctx-after-enter)
                           (:opts ctx-after-enter))
            
            ;; If terminated early (e.g., cache hit), response already in context
            ;; Otherwise call model and handle errors
            ctx-with-response (if (:terminated? ctx-after-enter)
                               ctx-after-enter
                               (try
                                 (let [response (chat model final-messages final-opts)]
                                   (assoc ctx-after-enter :response response))
                                 (catch Exception e
                                   ;; Model call failed, add to context for error handlers
                                   (assoc ctx-after-enter :error e))))
            
            ;; 5. Execute leave phase (will also run error handlers if error present)
            final-ctx (interceptor/execute-leave ctx-with-response)]
        
        ;; Check for errors after leave phase
        (if (:error final-ctx)
          (throw (:error final-ctx))
          
          ;; 6. Return response
          (:response final-ctx)))))))

(defn chat-with-interceptors-ctx
  "Execute chat with interceptor chain, returning full context.
  
  Same as chat-with-interceptors but returns the full context map instead
  of just the response. Useful for inspecting interceptor transformations,
  metadata, or debugging.
  
  Arguments:
    model        - ChatModel instance
    messages     - Vector of message maps
    interceptors - Vector of interceptor maps
    opts         - Optional chat options map
  
  Returns:
    Context map with keys:
      :model              - Original model
      :messages           - Original messages
      :opts               - Original options
      :response           - Final response
      :transformed-messages - Transformed messages (if any)
      :transformed-opts   - Transformed options (if any)
      :metadata           - Any metadata added by interceptors
      :queue              - Should be empty
      :stack              - Should be empty
      :error              - Error if occurred (will be thrown)
  
  Example:
    (let [ctx (chat-with-interceptors-ctx model messages [logging-int])]
      (println \"Original messages:\" (:messages ctx))
      (println \"Transformed messages:\" (:transformed-messages ctx))
      (println \"Response:\" (:response ctx))
      (println \"Metadata:\" (:metadata ctx)))"
  ([model messages interceptors]
   (chat-with-interceptors-ctx model messages interceptors {}))
  
  ([model messages interceptors opts]
   ;; Similar to chat-with-interceptors but return full context
   (let [initial-ctx (interceptor/context
                      {:model model
                       :messages messages
                       :opts opts
                       :queue interceptors})
         
         ctx-after-enter (interceptor/execute initial-ctx)]
     
     (if (:error ctx-after-enter)
       (throw (:error ctx-after-enter))
       
      (let [final-messages (or (:transformed-messages ctx-after-enter)
                               (:messages ctx-after-enter))
            final-opts (or (:transformed-opts ctx-after-enter)
                           (:opts ctx-after-enter))
            
            ctx-with-response (if (:terminated? ctx-after-enter)
                               ctx-after-enter
                               (try
                                 (let [response (chat model final-messages final-opts)]
                                   (assoc ctx-after-enter :response response))
                                 (catch Exception e
                                   (assoc ctx-after-enter :error e))))
            
            final-ctx (interceptor/execute-leave ctx-with-response)]
        
        (if (:error final-ctx)
          (throw (:error final-ctx))
          final-ctx))))))

