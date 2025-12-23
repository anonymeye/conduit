(ns conduit.stream
  "Streaming utilities for LLM responses using core.async.
  
  This namespace provides utilities for working with streaming LLM responses,
  including event collection, callbacks, timeouts, and SSE parsing."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [conduit.util.json :as json]))

;; Event types emitted during streaming
(def event-types
  "Set of valid stream event types."
  #{:message-start     ; Stream started, initial metadata
    :content-delta     ; Text content chunk
    :content-stop      ; Content block finished
    :tool-use-start    ; Tool call started
    :tool-use-delta    ; Tool arguments streaming
    :tool-use-stop     ; Tool call finished
    :message-end       ; Stream complete, includes usage
    :error})           ; Error occurred

;; Stream Collection

(defn stream->response
  "Collect stream events into a complete response.
  
  Consumes events from a core.async channel and assembles them into a single
  response map. Handles text content, tool calls, and usage information.
  
  Arguments:
    ch - core.async channel of stream events
  
  Returns:
    Channel that yields single complete Response map
  
  Example:
    (let [ch (c/stream model messages)
          response-ch (stream->response ch)]
      (println (<!! response-ch)))"
  [ch]
  (a/go-loop [content (StringBuilder.)
              tool-calls []
              usage nil
              stop-reason nil
              current-tool nil]
    (if-let [event (a/<! ch)]
      (case (:type event)
        :content-delta
        (do (.append content (get-in event [:delta :text] ""))
            (recur content tool-calls usage stop-reason current-tool))
        
        :tool-use-start
        (recur content tool-calls usage stop-reason
               {:id (get-in event [:tool-call :id])
                :name (get-in event [:tool-call :name])
                :arguments-json (StringBuilder.)})
        
        :tool-use-delta
        (do (when current-tool
              (.append (:arguments-json current-tool)
                       (get-in event [:delta :partial-json] "")))
            (recur content tool-calls usage stop-reason current-tool))
        
        :tool-use-stop
        (recur content
               (conj tool-calls
                     {:id (:id current-tool)
                      :type :function
                      :function {:name (:name current-tool)
                                 :arguments (json/decode 
                                              (str (:arguments-json current-tool)) 
                                              true)}})
               usage stop-reason nil)
        
        :message-end
        (recur content tool-calls (:usage event) (:stop-reason event) current-tool)
        
        :error
        {:error (:error event)}
        
        ;; Other event types (message-start, content-stop)
        (recur content tool-calls usage stop-reason current-tool))
      
      ;; Channel closed, return response
      {:role :assistant
       :content (str content)
       :tool-calls (when (seq tool-calls) tool-calls)
       :stop-reason stop-reason
       :usage usage})))

;; Stream Callbacks

(defn stream-with-callbacks
  "Stream events with callback functions.
  
  Processes stream events and invokes callbacks for different event types.
  Useful for real-time processing of streaming responses.
  
  Arguments:
    ch   - core.async channel of stream events
    opts - Map with callbacks:
           :on-start    - Called when stream starts (fn [event])
           :on-delta    - Called for each text delta (fn [text])
           :on-tool     - Called for each completed tool call (fn [tool-call])
           :on-complete - Called with final response (fn [response])
           :on-error    - Called on error (fn [error])
  
  Returns:
    Channel that yields final response when complete
  
  Example:
    (stream-with-callbacks ch
      {:on-delta #(print %)
       :on-complete #(println \"\\nDone!\")
       :on-error #(println \"Error:\" %)})"
  [ch {:keys [on-start on-delta on-tool on-complete on-error]
       :or {on-start identity
            on-delta identity
            on-tool identity
            on-complete identity
            on-error identity}}]
  (let [response-ch (a/chan 1)]
    (a/go
      (try
        (loop [content (StringBuilder.)
               tool-calls []
               usage nil
               stop-reason nil
               current-tool nil]
          (if-let [event (a/<! ch)]
            (case (:type event)
              :message-start
              (do (on-start event)
                  (recur content tool-calls usage stop-reason current-tool))
              
              :content-delta
              (let [text (get-in event [:delta :text] "")]
                (on-delta text)
                (.append content text)
                (recur content tool-calls usage stop-reason current-tool))
              
              :tool-use-start
              (recur content tool-calls usage stop-reason
                     {:id (get-in event [:tool-call :id])
                      :name (get-in event [:tool-call :name])
                      :arguments-json (StringBuilder.)})
              
              :tool-use-delta
              (do (when current-tool
                    (.append (:arguments-json current-tool)
                             (get-in event [:delta :partial-json] "")))
                  (recur content tool-calls usage stop-reason current-tool))
              
              :tool-use-stop
              (let [tc {:id (:id current-tool)
                        :type :function
                        :function {:name (:name current-tool)
                                   :arguments (json/decode 
                                                (str (:arguments-json current-tool)) 
                                                true)}}]
                (on-tool tc)
                (recur content (conj tool-calls tc) usage stop-reason nil))
              
              :message-end
              (recur content tool-calls (:usage event) (:stop-reason event) current-tool)
              
              :error
              (do (on-error (:error event))
                  (a/>! response-ch {:error (:error event)}))
              
              ;; Other events
              (recur content tool-calls usage stop-reason current-tool))
            
            ;; Stream complete
            (let [response {:role :assistant
                            :content (str content)
                            :tool-calls (when (seq tool-calls) tool-calls)
                            :stop-reason stop-reason
                            :usage usage}]
              (on-complete response)
              (a/>! response-ch response))))
        (catch Exception e
          (let [error {:type :error :message (.getMessage e)}]
            (on-error error)
            (a/>! response-ch {:error error})))
        (finally
          (a/close! response-ch))))
    response-ch))

;; Print Utilities

(defn print-stream
  "Print streaming content to stdout.
  
  Prints each text delta as it arrives and adds a newline when complete.
  
  Arguments:
    ch - core.async channel of stream events
  
  Returns:
    Channel that yields final response when complete
  
  Example:
    (<!! (print-stream (c/stream model messages)))"
  [ch]
  (stream-with-callbacks ch
    {:on-delta #(do (print %) (flush))
     :on-complete (fn [_] (println))}))

;; Timeout Handling

(defn with-stream-timeout
  "Add timeout to stream consumption.
  
  Wraps a stream channel with a timeout. If no events are received within
  the timeout period, an error event is emitted and the channel is closed.
  
  Arguments:
    ch         - Source channel
    timeout-ms - Timeout in milliseconds
  
  Returns:
    New channel that closes after timeout
  
  Example:
    (let [ch (c/stream model messages)
          timeout-ch (with-stream-timeout ch 30000)]
      (consume-stream timeout-ch))"
  [ch timeout-ms]
  (let [out (a/chan 100)]
    (a/go-loop []
      (let [[event port] (a/alts! [ch (a/timeout timeout-ms)])]
        (cond
          (= port ch)
          (if event
            (do (a/>! out event)
                (recur))
            (a/close! out))
          
          :else
          (do (a/>! out {:type :error 
                         :error {:type :timeout 
                                 :message "Stream timed out"}})
              (a/close! ch)
              (a/close! out)))))
    out))

;; SSE Parsing (for provider implementations)

(defn parse-sse-line
  "Parse a single Server-Sent Events (SSE) line.
  
  SSE format uses lines starting with 'data:', 'event:', 'id:', or ':' (comment).
  Empty lines signal the end of an event.
  
  Arguments:
    line - String line from SSE stream
  
  Returns:
    Map with :event, :data, or :id keys, or nil for empty/comment lines
  
  Example:
    (parse-sse-line \"data: {\\\"text\\\": \\\"hello\\\"}\")
    ;=> {:data \"{\\\"text\\\": \\\"hello\\\"}\"}"
  [line]
  (cond
    (str/blank? line) nil
    (str/starts-with? line ":") nil  ; Comment
    (str/starts-with? line "data: ")
    {:data (subs line 6)}
    (str/starts-with? line "event: ")
    {:event (subs line 7)}
    (str/starts-with? line "id: ")
    {:id (subs line 4)}
    :else nil))

(defn sse-lines->events
  "Transform SSE lines into parsed events.
  
  Accumulates SSE lines until an empty line is encountered, then emits
  the complete event. Handles multi-line events properly.
  
  Arguments:
    lines-ch - Channel of SSE lines (strings)
  
  Returns:
    Channel of parsed event maps
  
  Example:
    (let [lines-ch (a/chan)
          events-ch (sse-lines->events lines-ch)]
      (a/>!! lines-ch \"data: hello\")
      (a/>!! lines-ch \"\")
      (a/<!! events-ch))
    ;=> {:data \"hello\"}"
  [lines-ch]
  (let [out (a/chan 100)]
    (a/go-loop [current-event {}]
      (if-let [line (a/<! lines-ch)]
        (if (str/blank? line)
          ;; Empty line = event complete
          (do (when (seq current-event)
                (a/>! out current-event))
              (recur {}))
          ;; Parse line and accumulate
          (let [parsed (parse-sse-line line)]
            (recur (merge current-event parsed))))
        ;; Channel closed
        (do (when (seq current-event)
              (a/>! out current-event))
            (a/close! out))))
    out))

;; Backpressure Handling

(defn buffered-stream
  "Create a buffered stream that handles backpressure.
  
  Uses a sliding buffer that drops old events when the consumer can't keep up.
  Useful for preventing memory issues with slow consumers.
  
  Arguments:
    ch          - Source channel
    buffer-size - Buffer size (default: 100)
    on-drop     - Optional callback when events are dropped (fn [event])
  
  Returns:
    Buffered channel with sliding buffer
  
  Example:
    (let [ch (c/stream model messages)
          buffered (buffered-stream ch :buffer-size 50)]
      (consume-stream buffered))"
  [ch & {:keys [buffer-size on-drop]
         :or {buffer-size 100}}]
  (let [out (a/chan (a/sliding-buffer buffer-size))]
    (a/go-loop []
      (if-let [event (a/<! ch)]
        (if (a/>! out event)
          (recur)
          (do (when on-drop (on-drop event))
              (recur)))
        (a/close! out)))
    out))

;; Channel Utilities

(defn tap-stream
  "Tap into a stream without consuming it.
  
  Creates a mult from the source channel and taps it, allowing multiple
  consumers to receive the same events.
  
  Arguments:
    ch - Source channel
  
  Returns:
    Map with :mult (the mult) and :tap (fn to create new taps)
  
  Example:
    (let [{:keys [tap]} (tap-stream ch)
          ch1 (tap)
          ch2 (tap)]
      ;; Both ch1 and ch2 receive all events)"
  [ch]
  (let [m (a/mult ch)]
    {:mult m
     :tap (fn []
            (let [out (a/chan 100)]
              (a/tap m out)
              out))}))

(defn merge-streams
  "Merge multiple stream channels into one.
  
  All events from all input channels are forwarded to the output channel.
  The output channel closes when all input channels are closed.
  
  Arguments:
    chs - Collection of channels to merge
  
  Returns:
    Single merged channel
  
  Example:
    (let [ch1 (c/stream model1 messages)
          ch2 (c/stream model2 messages)
          merged (merge-streams [ch1 ch2])]
      (consume-stream merged))"
  [chs]
  (let [out (a/chan 100)]
    (a/go-loop [remaining (set chs)]
      (if (seq remaining)
        (let [[event ch] (a/alts! (vec remaining))]
          (if event
            (do (a/>! out event)
                (recur remaining))
            (recur (disj remaining ch))))
        (a/close! out)))
    out))

(defn filter-stream
  "Filter stream events by predicate.
  
  Only events that satisfy the predicate are forwarded to the output channel.
  
  Arguments:
    ch   - Source channel
    pred - Predicate function (fn [event] -> boolean)
  
  Returns:
    Filtered channel
  
  Example:
    (let [ch (c/stream model messages)
          deltas-only (filter-stream ch #(= :content-delta (:type %)))]
      (consume-stream deltas-only))"
  [ch pred]
  (let [out (a/chan 100)]
    (a/go-loop []
      (if-let [event (a/<! ch)]
        (do (when (pred event)
              (a/>! out event))
            (recur))
        (a/close! out)))
    out))

(defn map-stream
  "Transform stream events with a function.
  
  Applies a transformation function to each event before forwarding it.
  
  Arguments:
    ch - Source channel
    f  - Transformation function (fn [event] -> event)
  
  Returns:
    Transformed channel
  
  Example:
    (let [ch (c/stream model messages)
          uppercase (map-stream ch 
                      #(update-in % [:delta :text] str/upper-case))]
      (consume-stream uppercase))"
  [ch f]
  (let [out (a/chan 100)]
    (a/go-loop []
      (if-let [event (a/<! ch)]
        (do (a/>! out (f event))
            (recur))
        (a/close! out)))
    out))

