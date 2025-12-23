(ns conduit.agent
  "Agent loops for autonomous tool execution.
  
  Agents run iterative loops where the model can call tools, receive results,
  and continue until it reaches a final answer."
  (:require [conduit.core :as c]
            [conduit.stream :as stream]
            [conduit.tools :as tools]
            [clojure.core.async :as a]))

;; -----------------------------------------------------------------------------
;; Tool Loop

(defn tool-loop
  "Run a tool loop until completion or max iterations.
  
  The agent will:
  1. Call the model with messages and available tools
  2. If the model returns tool calls, execute them
  3. Add tool results to messages and repeat
  4. Continue until model returns without tool calls or max iterations reached
  
  Arguments:
    model    - ChatModel instance
    messages - Initial messages vector
    opts     - Options map:
               :tools          - Vector of tool definitions (required)
               :max-iterations - Max loop iterations (default: 10)
               :interceptors   - Vector of interceptors to apply to each chat call (optional)
               :on-response    - Callback (fn [response iteration] ...)
               :on-tool-call   - Callback (fn [tool-call] ...)
               :on-tool-result - Callback (fn [tool-call result] ...)
               :chat-opts      - Additional options to pass to chat (e.g. :temperature)
  
  Returns:
    Map with:
      :response        - Final response from model
      :messages        - Complete message history
      :iterations      - Number of iterations executed
      :tool-calls-made - Vector of all tool calls made
  
  Throws:
    ExceptionInfo with :type :max-iterations if max iterations reached
  
  Example:
    (tool-loop model
               [{:role :user :content \"What's the weather in Tokyo?\"}]
               {:tools [weather-tool]
                :max-iterations 5
                :interceptors [(retry-interceptor {:max-attempts 3})
                              (logging-interceptor {:level :info})]
                :on-tool-call #(println \"Calling:\" (:function %))})"
  [model messages {:keys [tools max-iterations interceptors on-response on-tool-call on-tool-result chat-opts]
                   :or {max-iterations 10}}]
  {:pre [(some? model)
         (vector? messages)
         (seq tools)
         (pos? max-iterations)]}
  
  ;; Choose chat function based on interceptors
  (let [chat-fn (if (seq interceptors)
                  ;; Use interceptor-aware chat
                  (fn [model msgs opts]
                    (c/chat-with-interceptors model msgs interceptors opts))
                  ;; Use regular chat (backward compatible)
                  c/chat)]
    
    (loop [msgs messages
           iteration 0
           all-tool-calls []]
      
      ;; Check max iterations
      (when (>= iteration max-iterations)
        (throw (ex-info "Max iterations reached"
                        {:type :max-iterations
                         :iteration iteration
                         :messages msgs
                         :tool-calls-made all-tool-calls})))
      
      ;; Call the model with tools (using chat-fn which handles interceptors)
      (let [response (chat-fn model msgs (merge chat-opts {:tools tools}))]
        
        ;; Invoke response callback
        (when on-response
          (on-response response iteration))
        
        (if-let [tool-calls (:tool-calls response)]
          ;; Execute tools and continue loop
          (let [tool-results 
                (mapv (fn [tc]
                        ;; Invoke tool call callback
                        (when on-tool-call
                          (on-tool-call tc))
                        
                        (let [tool-name (get-in tc [:function :name])
                              tool-args (get-in tc [:function :arguments])
                              tool (tools/find-tool tools tool-name)
                              result (if tool
                                       (try
                                         (tools/execute-tool tool tool-args)
                                         (catch Exception e
                                           (let [ex-data (ex-data e)
                                                 original-cause (get ex-data :cause)]
                                             {:error (if original-cause
                                                      (str (ex-message e) ": " original-cause)
                                                      (ex-message e))})))
                                       {:error (str "Unknown tool: " tool-name)})]
                          
                          ;; Invoke tool result callback
                          (when on-tool-result
                            (on-tool-result tc result))
                          
                          ;; Create tool message
                          {:role :tool
                           :tool-call-id (:id tc)
                           :content (tools/format-tool-result result)}))
                      tool-calls)]
            ;; Continue loop with updated messages (recur in tail position)
            (recur (-> msgs
                       (conj (dissoc response :usage :model :id))
                       (into tool-results))
                   (inc iteration)
                   (into all-tool-calls tool-calls)))
          
          ;; No tool calls - return final result
          {:response response
           :messages (conj msgs response)
           :iterations (inc iteration)
           :tool-calls-made all-tool-calls})))))

;; -----------------------------------------------------------------------------
;; Streaming Tool Loop

(defn streaming-tool-loop
  "Run a tool loop with streaming responses.
  
  Similar to tool-loop but streams each model response. Tool execution
  happens after each complete stream.
  
  Arguments:
    model    - ChatModel instance
    messages - Initial messages vector
    opts     - Options map (same as tool-loop plus):
               :interceptors   - Vector of interceptors (note: streaming interceptor support is limited)
               :on-stream-event - Callback (fn [event] ...) for stream events
  
  Returns:
    Same as tool-loop
  
  Example:
    (streaming-tool-loop model
                         [{:role :user :content \"Search and summarize\"}]
                         {:tools [search-tool]
                          :on-stream-event #(print (:content %))})"
  [model messages {:keys [tools max-iterations interceptors on-response on-tool-call 
                          on-tool-result on-stream-event chat-opts]
                   :or {max-iterations 10}}]
  {:pre [(some? model)
         (vector? messages)
         (seq tools)
         (pos? max-iterations)]}
  
  (loop [msgs messages
         iteration 0
         all-tool-calls []]
    
    ;; Check max iterations
    (when (>= iteration max-iterations)
      (throw (ex-info "Max iterations reached"
                      {:type :max-iterations
                       :iteration iteration
                       :messages msgs
                       :tool-calls-made all-tool-calls})))
    
    ;; Stream from the model
    ;; Note: interceptors parameter is accepted for API consistency but not yet
    ;; fully supported for streaming. Future enhancement will add streaming
    ;; interceptor support. The enter phase of interceptors could be applied here,
    ;; but leave phase would need to be handled differently for streaming responses.
    (let [_interceptors interceptors  ; Extracted but not yet used
          stream-ch (c/stream model msgs (merge chat-opts {:tools tools}))
          ;; Collect stream into response
          response (if on-stream-event
                     (a/<!! (stream/stream-with-callbacks 
                              stream-ch
                              {:on-delta on-stream-event
                               :on-complete identity}))
                     (a/<!! (stream/stream->response stream-ch)))]
      
      ;; Invoke response callback
      (when on-response
        (on-response response iteration))
      
      (if-let [tool-calls (:tool-calls response)]
        ;; Execute tools and continue loop
        (let [tool-results 
              (mapv (fn [tc]
                      (when on-tool-call
                        (on-tool-call tc))
                      
                      (let [tool-name (get-in tc [:function :name])
                            tool-args (get-in tc [:function :arguments])
                            tool (tools/find-tool tools tool-name)
                            result (if tool
                                     (try
                                       (tools/execute-tool tool tool-args)
                                       (catch Exception e
                                         (let [ex-data (ex-data e)
                                               original-cause (get ex-data :cause)]
                                           {:error (if original-cause
                                                    (str (ex-message e) ": " original-cause)
                                                    (ex-message e))})))
                                     {:error (str "Unknown tool: " tool-name)})]
                        
                        (when on-tool-result
                          (on-tool-result tc result))
                        
                        {:role :tool
                         :tool-call-id (:id tc)
                         :content (tools/format-tool-result result)}))
                    tool-calls)]
          
          (recur (-> msgs
                     (conj (dissoc response :usage :model :id))
                     (into tool-results))
                 (inc iteration)
                 (into all-tool-calls tool-calls)))
        
        ;; No tool calls - return final result
        {:response response
         :messages (conj msgs response)
         :iterations (inc iteration)
         :tool-calls-made all-tool-calls}))))

;; -----------------------------------------------------------------------------
;; Simple Agent

(defn make-agent
  "Create a simple agent that can use tools.
  
  Returns a function that takes a user message and returns the final response.
  
  Arguments:
    model - ChatModel instance
    tools - Vector of tool definitions
    opts  - Options map:
            :system-message - System message for the agent
            :max-iterations - Max tool loop iterations (default: 10)
            :interceptors   - Vector of interceptors to apply to each chat call (optional)
            :chat-opts      - Options to pass to chat
  
  Returns:
    Function (fn [user-message] -> response-map)
  
  Example:
    (def my-agent
      (make-agent model [weather-tool search-tool]
                  {:system-message \"You are a helpful assistant.\"}))
    
    (my-agent \"What's the weather in Tokyo?\")
    ;; => {:response {...} :messages [...] :iterations 2 ...}"
  [model tools & {:keys [system-message max-iterations interceptors chat-opts]
                  :or {max-iterations 10}}]
  {:pre [(some? model)
         (seq tools)]}
  
  (fn [user-message]
    (let [messages (cond-> []
                     system-message (conj (c/system-message system-message))
                     true (conj (c/user-message user-message)))]
      (tool-loop model messages
                 {:tools tools
                  :max-iterations max-iterations
                  :interceptors interceptors
                  :chat-opts chat-opts}))))

;; -----------------------------------------------------------------------------
;; Agent with History

(defn stateful-agent
  "Create a stateful agent that maintains conversation history.
  
  Returns a map with:
    :send!   - Function to send message and get response
    :history - Atom containing message history
    :reset!  - Function to reset conversation
  
  Arguments:
    model - ChatModel instance
    tools - Vector of tool definitions
    opts  - Options map (same as make-agent):
            :system-message - System message for the agent
            :max-iterations - Max tool loop iterations (default: 10)
            :interceptors   - Vector of interceptors to apply to each chat call (optional)
            :chat-opts      - Options to pass to chat
  
  Example:
    (def my-agent
      (stateful-agent model [weather-tool]
                      {:system-message \"You are helpful.\"}))
    
    ((:send! my-agent) \"Hello\")
    ((:send! my-agent) \"What's the weather?\")
    @(:history my-agent)  ;; See full conversation
    ((:reset! my-agent))  ;; Clear history"
  [model tools & {:keys [system-message max-iterations interceptors chat-opts]
                  :or {max-iterations 10}}]
  {:pre [(some? model)
         (seq tools)]}
  
  (let [history (atom (if system-message
                        [(c/system-message system-message)]
                        []))]
    {:history history
     
     :send! (fn [user-message]
              (swap! history conj (c/user-message user-message))
              (let [result (tool-loop model @history
                                      {:tools tools
                                       :max-iterations max-iterations
                                       :interceptors interceptors
                                       :chat-opts chat-opts})]
                (reset! history (:messages result))
                result))
     
     :reset! (fn []
               (reset! history (if system-message
                                 [(c/system-message system-message)]
                                 [])))}))

