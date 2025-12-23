(ns conduit.structured
  "Structured output using tool-based schema enforcement.
  
  Forces the model to return data in a specific structure by using tool calling
  with a schema-defined output tool."
  (:require [clojure.string :as str]
            [conduit.core :as c]
            [conduit.schema :as schema]
            [conduit.tools :as tools]))

;; -----------------------------------------------------------------------------
;; Structured Output

(defn with-structured-output
  "Wrap a model to enforce structured output via tool use.
  
  Creates a wrapper function that forces the model to use a specific tool
  for its output, ensuring the response matches the provided schema.
  
  Arguments:
    model  - ChatModel instance
    schema - Malli schema for expected output structure
    opts   - Options map:
             :name        - Tool name (default: \"output\")
             :description - Tool description (default: \"Provide your response...\")
             :strict      - Throw error if model doesn't use tool (default: true)
             :chat-opts   - Additional options to pass to chat
  
  Returns:
    Function (fn [messages] -> parsed-output-map)
  
  Throws:
    ExceptionInfo if model doesn't use output tool (when :strict true)
  
  Example:
    (def extract-entities
      (with-structured-output model
        [:map
         [:people [:vector :string]]
         [:places [:vector :string]]
         [:dates [:vector :string]]]
        {:name \"extract_entities\"
         :description \"Extract entities from the text\"}))
    
    (extract-entities [{:role :user 
                        :content \"John went to Paris on March 15th\"}])
    ;; => {:people [\"John\"] :places [\"Paris\"] :dates [\"March 15th\"]}"
  [model schema & {:keys [name description strict chat-opts]
                   :or {name "output"
                        description "Provide your response in this format"
                        strict true}}]
  {:pre [(some? model)
         (some? schema)]}
  
  (let [output-tool (tools/tool name description schema identity)]
    (fn [messages]
      (let [response (c/chat model messages
                             (merge chat-opts
                                    {:tools [output-tool]
                                     :tool-choice {:type :function
                                                   :function {:name name}}}))]
        (if-let [tool-calls (:tool-calls response)]
          (let [output-call (first tool-calls)]
            (get-in output-call [:function :arguments]))
          (if strict
            (throw (ex-info "Model did not use output tool"
                            {:type :structured-output-error
                             :response response
                             :expected-tool name}))
            ;; Non-strict mode: return response content
            {:content (:content response)}))))))

;; -----------------------------------------------------------------------------
;; Common Structured Output Patterns

(defn json-mode
  "Create a structured output function for JSON mode.
  
  Similar to with-structured-output but uses a more flexible approach
  that works with providers that support native JSON mode.
  
  Arguments:
    model  - ChatModel instance
    schema - Malli schema for expected output
    opts   - Options map (same as with-structured-output)
  
  Returns:
    Function (fn [messages] -> parsed-output-map)"
  [model schema & {:keys [name description chat-opts]
                   :or {name "json_output"
                        description "Output in JSON format"}}]
  (with-structured-output model schema
    :name name
    :description description
    :strict false
    :chat-opts (merge chat-opts {:response-format {:type "json_object"}})))

;; -----------------------------------------------------------------------------
;; Extraction Helpers

(defn extract-with-schema
  "Extract structured data from text using a schema.
  
  Convenience function that creates a one-shot extraction with a schema.
  
  Arguments:
    model  - ChatModel instance
    text   - Text to extract from
    schema - Malli schema for extraction
    opts   - Options map:
             :system-prompt - Custom system prompt (optional)
             :name          - Tool name (default: \"extract\")
             :description   - Tool description (default: \"Extract information...\")
  
  Returns:
    Extracted data matching schema
  
  Example:
    (extract-with-schema model
      \"John Smith works at Acme Corp in New York\"
      [:map
       [:name :string]
       [:company :string]
       [:location :string]])
    ;; => {:name \"John Smith\" :company \"Acme Corp\" :location \"New York\"}"
  [model text schema & {:keys [system-prompt name description]
                        :or {name "extract"
                             description "Extract information from the text"}}]
  {:pre [(some? model)
         (string? text)
         (some? schema)]}
  
  (let [extract-fn (with-structured-output model schema
                     :name name
                     :description description)
        messages (cond-> []
                   system-prompt (conj (c/system-message system-prompt))
                   true (conj (c/user-message text)))]
    (extract-fn messages)))

;; -----------------------------------------------------------------------------
;; Classification Helpers

(defn classify
  "Classify text into one of several categories.
  
  Arguments:
    model      - ChatModel instance
    text       - Text to classify
    categories - Vector of category strings or maps with :name and :description
    opts       - Options map:
                 :system-prompt - Custom system prompt (optional)
  
  Returns:
    Map with :category (string) and optional :confidence (if provided by model)
  
  Example:
    (classify model
      \"This product is amazing!\"
      [\"positive\" \"negative\" \"neutral\"])
    ;; => {:category \"positive\"}
    
    (classify model
      \"The weather is nice today\"
      [{:name \"weather\" :description \"About weather\"}
       {:name \"sports\" :description \"About sports\"}
       {:name \"other\" :description \"Other topics\"}])
    ;; => {:category \"weather\"}"
  [model text categories & {:keys [system-prompt]}]
  {:pre [(some? model)
         (string? text)
         (seq categories)]}
  
  (let [category-names (mapv #(if (string? %) % (:name %)) categories)
        schema [:map
                [:category [:enum {:decode/string keyword} 
                            (mapv keyword category-names)]]
                [:reasoning {:optional true} :string]]
        system-msg (or system-prompt
                       (str "Classify the text into one of these categories: "
                            (str/join ", " category-names)))
        result (extract-with-schema model text schema
                 :system-prompt system-msg
                 :name "classify"
                 :description "Classify the text into a category")]
    {:category (name (:category result))
     :reasoning (:reasoning result)}))

;; -----------------------------------------------------------------------------
;; Validation Helpers

(defn validate-structured-output
  "Validate structured output against schema.
  
  Arguments:
    schema - Malli schema
    data   - Data to validate
  
  Returns:
    Validated data
  
  Throws:
    ExceptionInfo on validation failure"
  [schema data]
  (schema/validate schema data))

(defn coerce-structured-output
  "Coerce structured output to match schema.
  
  Arguments:
    schema - Malli schema
    data   - Data to coerce
  
  Returns:
    Coerced data"
  [schema data]
  (schema/coerce schema data))

