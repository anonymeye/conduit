(ns conduit.schema
  "Malli schemas for all Conduit data types."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.json-schema :as json-schema]))

;; Content Block Types

(def TextBlock
  "Text content block"
  [:map
   [:type [:= :text]]
   [:text :string]])

(def ImageSource
  "Image source (base64 or URL)"
  [:map
   [:type [:enum :base64 :url]]
   [:media-type {:optional true} [:enum "image/jpeg" "image/png" "image/gif" "image/webp"]]
   [:data {:optional true} :string]      ; For base64
   [:url {:optional true} :string]])     ; For URL

(def ImageBlock
  "Image content block"
  [:map
   [:type [:= :image]]
   [:source ImageSource]])

(def ToolUseBlock
  "Tool use content block"
  [:map
   [:type [:= :tool-use]]
   [:id :string]
   [:name :string]
   [:input :map]])

(def ToolResultBlock
  "Tool result content block"
  [:map
   [:type [:= :tool-result]]
   [:tool-use-id :string]
   [:content [:or :string [:vector [:or TextBlock ImageBlock]]]]])

(def ContentBlock
  "Any content block type"
  [:or
   :string
   TextBlock
   ImageBlock
   ToolUseBlock
   ToolResultBlock])

;; Message Types

(def Role
  "Message role"
  [:enum :system :user :assistant :tool])

(def Message
  "Chat message"
  [:map
   [:role Role]
   [:content [:or :string [:vector ContentBlock]]]
   [:name {:optional true} :string]               ; For tool messages
   [:tool-call-id {:optional true} :string]])     ; For tool result messages

;; Tool Call Types

(def ToolCall
  "Tool invocation requested by model"
  [:map
   [:id :string]
   [:type [:= :function]]
   [:function 
    [:map
     [:name :string]
     [:arguments :map]]]])   ; Parsed JSON arguments

;; Response Types

(def StopReason
  "Reason the model stopped generating"
  [:enum :end-turn :tool-use :max-tokens :stop-sequence :content-filter])

(def Usage
  "Token usage information"
  [:map
   [:input-tokens :int]
   [:output-tokens :int]
   [:total-tokens {:optional true} :int]
   [:cache-read-tokens {:optional true} :int]
   [:cache-creation-tokens {:optional true} :int]])

(def Response
  "Unified response format from all providers"
  [:map
   [:id {:optional true} :string]
   [:role [:= :assistant]]
   [:content [:or :string [:vector ContentBlock]]]
   [:model :string]
   [:stop-reason StopReason]
   [:usage Usage]
   [:tool-calls {:optional true} [:vector ToolCall]]])

;; Tool Definition Types

(def Tool
  "Tool definition"
  [:map
   [:name :string]
   [:description :string]
   [:schema {:optional true} :any]              ; Malli schema for parameters
   [:parameters {:optional true} :map]          ; JSON Schema (alternative to :schema)
   [:fn {:optional true} fn?]])                 ; Implementation function

(def ToolResult
  "Result from tool execution"
  [:map
   [:tool-call-id :string]
   [:content [:or :string :map [:vector :any]]]
   [:is-error {:optional true} :boolean]])

;; Streaming Event Types

(def StreamEventType
  "Type of streaming event"
  [:enum 
   :message-start
   :content-delta
   :content-stop
   :tool-use-start
   :tool-use-delta
   :tool-use-stop
   :message-end
   :error])

(def StreamEvent
  "Streaming event"
  [:map
   [:type StreamEventType]
   [:index {:optional true} :int]
   [:delta {:optional true} [:map
                             [:text {:optional true} :string]
                             [:partial-json {:optional true} :string]]]
   [:content-block {:optional true} ContentBlock]
   [:message {:optional true} Response]
   [:usage {:optional true} Usage]
   [:error {:optional true} :map]])

;; Model Configuration Types

(def ModelConfig
  "Model configuration"
  [:map
   [:model :string]
   [:api-key {:optional true} :string]
   [:base-url {:optional true} :string]
   [:timeout-ms {:optional true} :int]
   [:max-retries {:optional true} :int]])

(def ChatOptions
  "Options for chat and stream functions"
  [:map
   [:temperature {:optional true} [:double {:min 0.0 :max 2.0}]]
   [:max-tokens {:optional true} :int]
   [:top-p {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:top-k {:optional true} :int]
   [:stop {:optional true} [:or :string [:vector :string]]]
   [:tools {:optional true} [:vector Tool]]
   [:tool-choice {:optional true} [:or 
                                   [:enum :auto :none :required]
                                   [:map [:type [:= :function]] [:function [:map [:name :string]]]]]]
   [:response-format {:optional true} [:enum :text :json]]
   [:response-schema {:optional true} :any]     ; Malli schema for structured output
   [:seed {:optional true} :int]
   [:user {:optional true} :string]
   [:metadata {:optional true} :map]])

;; Provider Capability Types

(def Capabilities
  "Provider capabilities"
  [:map
   [:streaming :boolean]
   [:tool-calling :boolean]
   [:vision :boolean]
   [:json-mode :boolean]
   [:structured-output {:optional true} :boolean]
   [:prompt-caching {:optional true} :boolean]
   [:extended-thinking {:optional true} :boolean]
   [:pdf-support {:optional true} :boolean]
   [:audio {:optional true} :boolean]
   [:max-context :int]
   [:max-output {:optional true} :int]])

;; RAG Types

(def Document
  "Document for RAG"
  [:map
   [:id {:optional true} :string]
   [:content :string]
   [:metadata {:optional true} :map]
   [:embedding {:optional true} [:vector :double]]])

(def RetrievalResult
  "Result from retrieval"
  [:map
   [:document Document]
   [:score :double]
   [:rank :int]])

;; Error Types

(def ErrorType
  "Error type"
  [:enum
   :rate-limit
   :authentication
   :authorization
   :invalid-request
   :not-found
   :server-error
   :timeout
   :network
   :validation
   :tool-error
   :max-iterations
   :max-tokens
   :content-filter])

(def ErrorData
  "Error data structure"
  [:map
   [:type ErrorType]
   [:message :string]
   [:provider {:optional true} :keyword]
   [:status {:optional true} :int]
   [:retry-after {:optional true} :int]
   [:request-id {:optional true} :string]
   [:details {:optional true} :map]])

;; Validation Helpers

(defn validate
  "Validate data against schema, returns data or throws.
  
  Arguments:
    schema - Malli schema
    data   - Data to validate
  
  Returns:
    data if valid
  
  Throws:
    ExceptionInfo with :type :validation if invalid"
  [schema data]
  (if (m/validate schema data)
    data
    (throw (ex-info "Validation failed" 
                    {:type :validation
                     :errors (me/humanize (m/explain schema data))
                     :data data}))))

(defn coerce
  "Coerce data to match schema (e.g., strings to keywords).
  
  Arguments:
    schema - Malli schema
    data   - Data to coerce
  
  Returns:
    Coerced data"
  [schema data]
  (m/coerce schema data mt/string-transformer))

(defn valid?
  "Check if data matches schema.
  
  Arguments:
    schema - Malli schema
    data   - Data to check
  
  Returns:
    true if valid, false otherwise"
  [schema data]
  (m/validate schema data))

(defn explain
  "Return human-readable explanation of validation errors.
  
  Arguments:
    schema - Malli schema
    data   - Data to explain
  
  Returns:
    Map of errors or nil if valid"
  [schema data]
  (when-let [explanation (m/explain schema data)]
    (me/humanize explanation)))

(defn validator
  "Create a validator function for a schema.
  
  Arguments:
    schema - Malli schema
  
  Returns:
    Function that takes data and returns true/false"
  [schema]
  (m/validator schema))

(defn explainer
  "Create an explainer function for a schema.
  
  Arguments:
    schema - Malli schema
  
  Returns:
    Function that takes data and returns explanation or nil"
  [schema]
  (let [explain-fn (m/explainer schema)]
    (fn [data]
      (when-let [explanation (explain-fn data)]
        (me/humanize explanation)))))

;; JSON Schema Conversion

(defn malli->json-schema
  "Convert Malli schema to JSON Schema for tool parameters.
  
  Arguments:
    schema - Malli schema
  
  Returns:
    JSON Schema map
  
  Example:
    (malli->json-schema [:map [:name :string] [:age :int]])
    => {:type \"object\"
        :properties {:name {:type \"string\"} :age {:type \"integer\"}}
        :required [\"name\" \"age\"]}"
  [schema]
  (json-schema/transform schema))

;; Schema Registry

(def registry
  "Registry of all Conduit schemas"
  {:content-block ContentBlock
   :text-block TextBlock
   :image-block ImageBlock
   :image-source ImageSource
   :tool-use-block ToolUseBlock
   :tool-result-block ToolResultBlock
   :role Role
   :message Message
   :tool-call ToolCall
   :stop-reason StopReason
   :usage Usage
   :response Response
   :tool Tool
   :tool-result ToolResult
   :stream-event-type StreamEventType
   :stream-event StreamEvent
   :model-config ModelConfig
   :chat-options ChatOptions
   :capabilities Capabilities
   :document Document
   :retrieval-result RetrievalResult
   :error-type ErrorType
   :error-data ErrorData})

(defn get-schema
  "Get schema by keyword from registry.
  
  Arguments:
    k - Schema keyword
  
  Returns:
    Malli schema or nil"
  [k]
  (get registry k))

