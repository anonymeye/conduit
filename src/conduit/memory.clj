(ns conduit.memory
  "Conversation memory and state management.
  
  Provides utilities for managing conversation history, windowing,
  summarization, and persistence."
  (:require [conduit.core :as c]))

;; -----------------------------------------------------------------------------
;; Basic Conversation Memory

(defn conversation
  "Create a new conversation memory store.
  
  Returns:
    Atom containing {:messages [] :metadata {}}"
  []
  (atom {:messages []
         :metadata {:created-at (System/currentTimeMillis)}}))

(defn add!
  "Add a message to conversation memory.
  
  Arguments:
    conv    - Conversation atom
    message - Message map
  
  Example:
    (add! conv {:role :user :content \"Hello\"})"
  [conv message]
  (swap! conv update :messages conj message))

(defn messages
  "Get all messages from conversation.
  
  Arguments:
    conv - Conversation atom
  
  Returns:
    Vector of messages"
  [conv]
  (:messages @conv))

(defn clear!
  "Clear all messages from conversation.
  
  Arguments:
    conv - Conversation atom"
  [conv]
  (swap! conv assoc :messages []))

(defn set-metadata!
  "Set metadata on conversation.
  
  Arguments:
    conv - Conversation atom
    key  - Metadata key
    value - Metadata value"
  [conv key value]
  (swap! conv assoc-in [:metadata key] value))

(defn get-metadata
  "Get metadata from conversation.
  
  Arguments:
    conv - Conversation atom
    key  - Metadata key (optional)
  
  Returns:
    Metadata map or value for key"
  ([conv]
   (:metadata @conv))
  ([conv key]
   (get-in @conv [:metadata key])))

;; -----------------------------------------------------------------------------
;; Windowed Memory

(defn window
  "Create a windowed view of conversation memory.
  
  Arguments:
    conv - Conversation atom
    n    - Number of messages to keep
  
  Returns:
    Function that returns last n messages"
  [conv n]
  (fn []
    (let [msgs (:messages @conv)]
      (if (<= (count msgs) n)
        msgs
        (vec (take-last n msgs))))))

(defn windowed-messages
  "Get last n messages from conversation.
  
  Arguments:
    conv - Conversation atom
    n    - Number of messages to return
  
  Returns:
    Vector of last n messages"
  [conv n]
  ((window conv n)))

;; -----------------------------------------------------------------------------
;; Token-Limited Memory

(defn estimate-tokens
  "Rough token estimate (4 chars per token).
  
  Arguments:
    content - Message content (string or other)
  
  Returns:
    Estimated token count"
  [content]
  (if (string? content)
    (int (/ (count content) 4))
    100))  ; Default for non-string content

(defn token-window
  "Create a token-limited view of conversation memory.
  
  Arguments:
    conv       - Conversation atom
    max-tokens - Maximum tokens to include
    count-fn   - Token counting function (default: estimate-tokens)
  
  Returns:
    Function that returns messages within token budget"
  [conv max-tokens & {:keys [count-fn]
                      :or {count-fn estimate-tokens}}]
  (fn []
    (let [msgs (reverse (:messages @conv))]
      (loop [result []
             remaining max-tokens
             [msg & rest] msgs]
        (if (nil? msg)
          (vec (reverse result))
          (let [tokens (count-fn (:content msg))]
            (if (> tokens remaining)
              (vec (reverse result))
              (recur (conj result msg)
                     (- remaining tokens)
                     rest))))))))

(defn token-limited-messages
  "Get messages within token budget.
  
  Arguments:
    conv       - Conversation atom
    max-tokens - Maximum tokens
    count-fn   - Token counting function (optional)
  
  Returns:
    Vector of messages within token budget"
  [conv max-tokens & {:keys [count-fn]}]
  (if count-fn
    ((token-window conv max-tokens :count-fn count-fn))
    ((token-window conv max-tokens))))

;; -----------------------------------------------------------------------------
;; Summarizing Memory

(defn summarize-messages
  "Summarize messages using an LLM.
  
  Arguments:
    model    - ChatModel for summarization
    messages - Messages to summarize
  
  Returns:
    Summary string"
  [model messages]
  (let [prompt (str "Summarize this conversation concisely:\n\n"
                    (pr-str messages))]
    (:content (c/chat model [{:role :user :content prompt}]))))

(defn summarizing-memory
  "Create memory that summarizes old messages.
  
  Arguments:
    conv           - Conversation atom
    _model         - ChatModel for summarization (unused, summary must be pre-computed)
    recent-count   - Number of recent messages to keep verbatim
    summarize-at   - Message count that triggers summarization
  
  Returns:
    Function that returns messages with summary"
  [conv _model recent-count summarize-at]
  (fn []
    (let [msgs (:messages @conv)
          summary (:summary @conv)]
      (if (< (count msgs) summarize-at)
        ;; Not enough messages to summarize
        msgs
        ;; Include summary + recent messages
        (let [recent (take-last recent-count msgs)
              summary-msg {:role :system
                           :content (str "Previous conversation summary: " summary)}]
          (into [summary-msg] recent))))))

(defn maybe-summarize!
  "Summarize conversation if it exceeds threshold.
  
  Arguments:
    conv         - Conversation atom
    model        - ChatModel for summarization
    threshold    - Message count threshold
    keep-recent  - Messages to keep after summary"
  [conv model threshold keep-recent]
  (let [msgs (:messages @conv)]
    (when (> (count msgs) threshold)
      (let [to-summarize (drop-last keep-recent msgs)
            recent (take-last keep-recent msgs)
            new-summary (summarize-messages model to-summarize)
            existing-summary (:summary @conv)
            combined-summary (if existing-summary
                             (str existing-summary "\n\n" new-summary)
                             new-summary)]
        (swap! conv assoc
               :messages (vec recent)
               :summary combined-summary)))))

;; -----------------------------------------------------------------------------
;; Persistent Memory

(defn save-conversation
  "Save conversation to file.
  
  Arguments:
    conv - Conversation atom
    path - File path"
  [conv path]
  (spit path (pr-str @conv)))

(defn load-conversation
  "Load conversation from file.
  
  Arguments:
    path - File path
  
  Returns:
    Conversation atom"
  [path]
  (atom (read-string (slurp path))))

(defn export-json
  "Export conversation to JSON.
  
  Arguments:
    conv - Conversation atom
    path - File path"
  [conv path]
  (require '[conduit.util.json :as json])
  (spit path ((resolve 'conduit.util.json/encode) @conv)))

;; -----------------------------------------------------------------------------
;; Memory with Retrieval

(defn retrieval-memory
  "Create memory that retrieves relevant past messages.
  
  This is a placeholder for RAG integration. Full implementation
  requires conduit.rag modules.
  
  Arguments:
    conv       - Conversation atom
    _embed-fn  - Function to embed text (unused until RAG integration)
    _store     - Vector store (unused until RAG integration)
    _k         - Number of messages to retrieve (unused until RAG integration)
  
  Returns:
    Function that returns relevant + recent messages"
  [conv _embed-fn _store _k]
  (fn [query]
    (let [_query query
          ;; This would use RAG retrieval when available
          ;; relevant (similarity-search store query-embedding k)
          recent (take-last 3 (:messages @conv))]
      ;; For now, just return recent messages
      ;; TODO: Integrate with conduit.rag when available
      (->> recent
           (map-indexed (fn [idx msg] (assoc msg :idx idx)))
           (sort-by (fn [msg] (or (:timestamp msg) 0)))
           vec))))

(defn add-with-embedding!
  "Add message to conversation and vector store.
  
  This is a placeholder for RAG integration.
  
  Arguments:
    conv      - Conversation atom
    _embed-fn - Function to embed text (unused until RAG integration)
    _store    - Vector store (unused until RAG integration)
    message   - Message map"
  [conv _embed-fn _store message]
  (let [msg-with-id (assoc message 
                           :id (str (java.util.UUID/randomUUID))
                           :timestamp (System/currentTimeMillis))]
    (add! conv msg-with-id)
    ;; TODO: Integrate with vector store when available
    ;; (let [embedding (embed-fn (:content message))]
    ;;   (add-documents store [msg-with-id] [embedding]))
    ))

;; -----------------------------------------------------------------------------
;; Usage Helpers

(defn chat-with-memory
  "Helper function for chatting with memory.
  
  Arguments:
    model    - ChatModel instance
    conv     - Conversation atom
    message  - User message string
    opts     - Options:
               :memory-fn - Function to get messages (default: messages)
               :chat-opts - Options to pass to chat
  
  Returns:
    Response content string"
  [model conv message {:keys [memory-fn chat-opts]
                        :or {memory-fn messages}}]
  (add! conv {:role :user :content message})
  (let [msgs (memory-fn conv)
        response (c/chat model msgs (or chat-opts {}))]
    (add! conv response)
    (:content response)))

