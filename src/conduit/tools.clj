(ns conduit.tools
  "Tool definitions and execution for function calling.
  
  Tools enable LLMs to call functions and interact with external systems.
  A tool is a map with a name, description, schema, and implementation function."
  (:require [conduit.schema :as schema]
            [conduit.util.json :as json]))

;; -----------------------------------------------------------------------------
;; Tool Creation

(defn tool
  "Create a tool definition with validation.
  
  Arguments:
    name        - Tool name (string)
    description - What the tool does (string)
    schema      - Malli schema for parameters
    f           - Implementation function (args-map -> result)
  
  Returns:
    Tool map with :name, :description, :schema, :fn
  
  Example:
    (tool \"get_weather\"
          \"Get the current weather for a location\"
          [:map [:location :string] [:unit {:optional true} [:enum \"celsius\" \"fahrenheit\"]]]
          (fn [{:keys [location unit]}]
            {:temperature 22 :unit (or unit \"celsius\") :location location}))"
  [name description schema f]
  {:pre [(string? name)
         (string? description)
         (some? schema)
         (fn? f)]}
  {:name name
   :description description
   :schema schema
   :fn f})

;; -----------------------------------------------------------------------------
;; Tool Validation

(defn validate-tool-args
  "Validate tool arguments against schema.
  
  Arguments:
    tool - Tool definition map with :schema
    args - Arguments map to validate
  
  Returns:
    Validated args map
  
  Throws:
    ExceptionInfo on validation failure"
  [{:keys [schema]} args]
  (when schema
    (schema/validate schema args))
  args)

;; -----------------------------------------------------------------------------
;; Tool Execution

(defn execute-tool
  "Execute a tool with arguments.
  
  Arguments:
    tool - Tool definition map with :fn
    args - Arguments map
  
  Returns:
    Tool result (any)
  
  Throws:
    ExceptionInfo on validation failure or execution error"
  [tool args]
  (try
    (let [validated-args (validate-tool-args tool args)]
      ((:fn tool) validated-args))
    (catch Exception e
      ;; Wrap all errors (validation and execution) with context
      (throw (ex-info "Tool execution failed"
                      {:type :tool-error
                       :tool (:name tool)
                       :args args
                       :cause (ex-message e)
                       :original-error e})))))

;; -----------------------------------------------------------------------------
;; Tool Lookup

(defn find-tool
  "Find a tool by name in a collection.
  
  Arguments:
    tools - Collection of tool maps
    name  - Tool name to find (string)
  
  Returns:
    Tool map or nil if not found"
  [tools name]
  (first (filter #(= name (:name %)) tools)))

;; -----------------------------------------------------------------------------
;; Tool Result Formatting

(defn format-tool-result
  "Format tool result as message content string.
  
  Arguments:
    result - Tool result (any)
  
  Returns:
    String representation of result (JSON for non-strings)"
  [result]
  (if (string? result)
    result
    (json/encode result)))

;; -----------------------------------------------------------------------------
;; Tool Call Execution

(defn execute-tool-calls
  "Execute all tool calls and return results as tool messages.
  
  Arguments:
    tools      - Collection of tool definitions
    tool-calls - Vector of tool call maps with :id, :function {:name :arguments}
  
  Returns:
    Vector of tool result messages with :role :tool, :tool-call-id, :content
  
  Example:
    (execute-tool-calls [weather-tool search-tool]
                        [{:id \"call_1\"
                          :function {:name \"get_weather\"
                                     :arguments {:location \"Tokyo\"}}}])
    ;; => [{:role :tool
    ;;      :tool-call-id \"call_1\"
    ;;      :content \"{\\\"temperature\\\":22,...}\"}]"
  [tools tool-calls]
  (mapv (fn [{:keys [id function]}]
          (let [tool-name (:name function)
                tool-args (:arguments function)
                tool (find-tool tools tool-name)
                result (if tool
                         (try
                           {:content (format-tool-result (execute-tool tool tool-args))}
                           (catch Exception e
                             {:content (format-tool-result {:error (ex-message e)})
                              :is-error true}))
                         {:content (format-tool-result {:error (str "Unknown tool: " tool-name)})
                          :is-error true})]
            (merge {:role :tool
                    :tool-call-id id}
                   result)))
        tool-calls))

;; -----------------------------------------------------------------------------
;; Schema Conversion

(defn malli->json-schema
  "Convert Malli schema to JSON Schema for tool parameters.
  
  Arguments:
    malli-schema - Malli schema
  
  Returns:
    JSON Schema map"
  [malli-schema]
  (schema/malli->json-schema malli-schema))

(defn tool->json-schema
  "Convert tool definition to JSON Schema format for API requests.
  
  Arguments:
    tool - Tool definition map
  
  Returns:
    Map with :type \"function\", :function {:name :description :parameters}
  
  Example:
    (tool->json-schema weather-tool)
    ;; => {:type \"function\"
    ;;     :function {:name \"get_weather\"
    ;;                :description \"Get the current weather...\"
    ;;                :parameters {...json schema...}}}"
  [{:keys [name description schema]}]
  {:type "function"
   :function {:name name
              :description description
              :parameters (malli->json-schema schema)}})

(defn tools->json-schema
  "Convert collection of tools to JSON Schema format.
  
  Arguments:
    tools - Collection of tool definitions
  
  Returns:
    Vector of JSON Schema tool definitions"
  [tools]
  (mapv tool->json-schema tools))

