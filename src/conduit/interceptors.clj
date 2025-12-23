(ns conduit.interceptors
  "Built-in interceptors for common cross-cutting concerns.
  
  Interceptors provide a data-driven way to compose cross-cutting functionality
  without coupling to the ChatModel protocol. Each interceptor is a map with
  optional :enter, :leave, and :error functions.
  
  Usage:
    (require '[conduit.interceptor :as interceptor]
             '[conduit.interceptors :as interceptors])
    
    (c/chat-with-interceptors
      model
      messages
      [(interceptors/retry-interceptor {:max-attempts 3})
       (interceptors/logging-interceptor {:level :info})])
  
  See docs/INTERCEPTOR_MIGRATION_PLAN.md for more information."
  (:require [conduit.core :as c]
            [conduit.error :as error]
            [conduit.interceptor :as interceptor]))

;;; -----------------------------------------------------------------------------
;;; Retry Interceptor

(defn retry-interceptor
  "Retry failed requests with exponential backoff.
  
  Options:
    :max-attempts     - Maximum retry attempts (default: 3)
    :initial-delay-ms - Initial delay between retries (default: 1000)
    :max-delay-ms     - Maximum delay between retries (default: 30000)
    :multiplier       - Delay multiplier per attempt (default: 2.0)
    :jitter           - Random jitter factor 0-1 (default: 0.1)
    :retryable?       - Predicate to check if error is retryable
                        (default: rate-limit, server-error, timeout)
  
  Returns:
    Interceptor map"
  [{:keys [max-attempts initial-delay-ms max-delay-ms multiplier jitter retryable?]
    :or {max-attempts 3
         initial-delay-ms 1000
         max-delay-ms 30000
         multiplier 2.0
         jitter 0.1
         retryable? error/retryable?}}]
  {:name :retry
   :error (fn [ctx err]
            (let [retry-count (get-in ctx [:metadata :retry-count] 0)
                  current-delay (get-in ctx [:metadata :retry-delay] initial-delay-ms)]
              (if (and (< retry-count max-attempts)
                       (retryable? err))
                ;; Retry: clear error and set retry flag
                (let [retry-after (or (:retry-after (ex-data err))
                                      current-delay)
                      actual-delay (+ retry-after 
                                      (long (* retry-after jitter (rand))))
                      next-delay (min (* current-delay multiplier) max-delay-ms)]
                  (Thread/sleep actual-delay)
                  (-> ctx
                      (update-in [:metadata :retry-count] (fnil inc 0))
                      (assoc-in [:metadata :retry-delay] next-delay)
                      (assoc :retry? true)
                      (interceptor/clear-error)))
                ;; Don't retry: keep error
                ctx)))})

;;; -----------------------------------------------------------------------------
;;; Logging Interceptor

(defn logging-interceptor
  "Log requests and responses.
  
  Options:
    :logger      - Logger function (default: println)
    :level       - Log level :debug, :info, :warn (default: :info)
    :log-request - Log request details? (default: true)
    :log-response - Log response details? (default: true)
    :redact-keys - Keys to redact from logs (default: #{:api-key})
  
  Returns:
    Interceptor map"
  [{:keys [logger level log-request log-response redact-keys]
    :or {logger println
         level :info
         log-request true
         log-response true
         redact-keys #{:api-key}}}]
  (let [request-id (atom nil)
        start-time (atom nil)]
    {:name :logging
     :enter (fn [ctx]
              (when log-request
                (let [id (str (java.util.UUID/randomUUID))]
                  (reset! request-id id)
                  (reset! start-time (System/currentTimeMillis))
                  (logger {:level level
                           :event :request
                           :request-id id
                           :model (c/model-name (:model ctx))
                           :message-count (count (:messages ctx))
                           :has-tools (boolean (get-in ctx [:opts :tools]))})))
              ctx)
     :leave (fn [ctx]
              (when log-response
                (let [elapsed (- (System/currentTimeMillis) @start-time)]
                  (logger {:level level
                           :event :response
                           :request-id @request-id
                           :elapsed-ms elapsed
                           :stop-reason (get-in ctx [:response :stop-reason])
                           :usage (get-in ctx [:response :usage])})))
              ctx)
     :error (fn [ctx err]
              (logger {:level :error
                       :event :error
                       :request-id @request-id
                       :error-type (error/error-type err)
                       :message (.getMessage err)})
              ctx)}))

;;; -----------------------------------------------------------------------------
;;; Cost Tracking Interceptor

(def pricing
  "Pricing per 1M tokens (input/output)"
  {:grok-3 {:input 3.00 :output 15.00}
   :grok-3-fast {:input 5.00 :output 25.00}
   :grok-3-mini {:input 0.30 :output 0.50}
   :grok-3-mini-fast {:input 0.60 :output 4.00}
   :claude-sonnet-4-20250514 {:input 3.00 :output 15.00}
   :claude-3-5-haiku-20241022 {:input 0.80 :output 4.00}
   :gpt-4o {:input 2.50 :output 10.00}
   :gpt-4o-mini {:input 0.15 :output 0.60}})

(defn- calculate-cost [model-name usage]
  (let [prices (get pricing (keyword model-name))
        input-cost (/ (* (:input-tokens usage 0) (:input prices 0)) 1000000)
        output-cost (/ (* (:output-tokens usage 0) (:output prices 0)) 1000000)]
    {:input-cost input-cost
     :output-cost output-cost
     :total-cost (+ input-cost output-cost)}))

(defn cost-tracking-interceptor
  "Track usage and costs.
  
  Options:
    :on-usage - Callback function receiving {:usage ... :cost ... :model ...}
  
  Returns:
    Interceptor map"
  [{:keys [on-usage]
    :or {on-usage identity}}]
  {:name :cost-tracking
   :leave (fn [ctx]
            (when-let [usage (get-in ctx [:response :usage])]
              (let [model-name (c/model-name (:model ctx))
                    cost (calculate-cost model-name usage)]
                (on-usage {:model model-name
                           :usage usage
                           :cost cost})))
            ctx)})

;;; -----------------------------------------------------------------------------
;;; Cache Interceptor

(defn cache-interceptor
  "Cache responses based on message content hash.
  
  Options:
    :store   - Cache store (atom, redis client, etc.)
               Must support get/put operations
    :ttl-ms  - Time to live in milliseconds (default: 3600000 = 1 hour)
    :key-fn  - Function to generate cache key (default: hash of messages + opts)
    :skip?   - Predicate to skip caching for certain requests
  
  Returns:
    Interceptor map"
  [{:keys [store ttl-ms key-fn skip?]
    :or {store (atom {})
         ttl-ms 3600000
         key-fn (fn [messages opts] 
                 (hash [messages (dissoc opts :tools)]))
         skip? (constantly false)}}]
  {:name :cache
   :enter (fn [ctx]
            (if (skip? (:messages ctx) (:opts ctx))
              ctx
              (let [cache-key (key-fn (:messages ctx) (:opts ctx))
                    cached (get @store cache-key)]
                (if (and cached 
                         (< (- (System/currentTimeMillis) (:timestamp cached)) ttl-ms))
                  ;; Cache hit: set response and terminate
                  (-> ctx
                      (assoc :response (:response cached)
                             :cached? true)
                      (interceptor/terminate))
                  ;; Cache miss: continue
                  ctx))))
   :leave (fn [ctx]
            ;; Only cache if it wasn't already cached
            (when (and (not (:cached? ctx))
                       (not (skip? (:messages ctx) (:opts ctx))))
              (let [cache-key (key-fn (:messages ctx) (:opts ctx))]
                (swap! store assoc cache-key 
                       {:response (:response ctx)
                        :timestamp (System/currentTimeMillis)})))
            ctx)})

;;; -----------------------------------------------------------------------------
;;; Timeout Interceptor

(defn timeout-interceptor
  "Add timeout to requests.
  
  Note: This interceptor sets a timeout flag in the context. The actual
  timeout enforcement should be handled at the model call level (e.g., using
  future with timeout).
  
  Options:
    :timeout-ms - Timeout in milliseconds (default: 60000)
  
  Returns:
    Interceptor map"
  [{:keys [timeout-ms]
    :or {timeout-ms 60000}}]
  {:name :timeout
   :enter (fn [ctx]
            (assoc-in ctx [:metadata :timeout-at] 
                     (+ (System/currentTimeMillis) timeout-ms)))})

;;; -----------------------------------------------------------------------------
;;; Rate Limit Interceptor

(defn rate-limit-interceptor
  "Apply token bucket rate limiting.
  
  Options:
    :requests-per-minute - Max requests per minute (default: 60)
    :tokens-per-minute   - Max tokens per minute (optional)
    :burst-size          - Max burst size (default: requests-per-minute / 6)
  
  Returns:
    Interceptor map"
  [{:keys [requests-per-minute burst-size]
    :or {requests-per-minute 60}}]
  (let [bucket (atom {:tokens (or burst-size (/ requests-per-minute 6))
                      :last-update (System/currentTimeMillis)})
        refill-rate (/ requests-per-minute 60000.0)]
    {:name :rate-limit
     :enter (fn [ctx]
              (loop []
                (let [now (System/currentTimeMillis)
                      {:keys [tokens last-update]} @bucket
                      elapsed (- now last-update)
                      new-tokens (min (or burst-size (/ requests-per-minute 6))
                                      (+ tokens (* elapsed refill-rate)))]
                  (if (>= new-tokens 1)
                    (do
                      (swap! bucket assoc 
                             :tokens (dec new-tokens)
                             :last-update now)
                      ctx)
                    (do
                      (Thread/sleep 100)
                      (recur))))))}))

;;; -----------------------------------------------------------------------------
;;; Context Management Interceptors

(defn token-limit-interceptor
  "Trim messages to fit within token limit.
  
  This is a simplified version that trims from the beginning (oldest messages).
  For more sophisticated token counting, you may need to integrate with a
  tokenizer library.
  
  Arguments:
    max-tokens - Maximum number of tokens allowed
    opts       - Options map (optional)
  
  Options:
    :token-count-fn - Function to count tokens in messages (default: rough estimate)
    :preserve-system - Keep system messages? (default: true)
  
  Returns:
    Interceptor map"
  ([max-tokens]
   (token-limit-interceptor max-tokens {}))
  ([max-tokens {:keys [token-count-fn preserve-system]
                :or {preserve-system true
                     token-count-fn (fn [messages]
                                     ;; Rough estimate: ~4 chars per token
                                     (long (/ (reduce + (map (comp count :content str) messages)) 4)))}}]
   {:name :token-limit
    :enter (fn [ctx]
             (let [messages (:messages ctx)
                   system-msgs (if preserve-system
                                (filter #(= :system (:role %)) messages)
                                [])
                   other-msgs (if preserve-system
                               (remove #(= :system (:role %)) messages)
                               messages)
                   token-count (token-count-fn other-msgs)]
               (if (<= token-count max-tokens)
                 ctx
                 ;; Need to trim - simple approach: take last N messages
                 (let [trimmed (loop [remaining other-msgs
                                      result []]
                                (if (empty? remaining)
                                  result
                                  (let [candidate (conj result (first remaining))
                                        count (token-count-fn candidate)]
                                    (if (<= count max-tokens)
                                      (recur (rest remaining) candidate)
                                      result))))
                       final-messages (concat system-msgs trimmed)]
                   (assoc ctx :transformed-messages final-messages)))))}))

(defn sliding-window-interceptor
  "Keep only the last N messages.
  
  Arguments:
    n     - Number of messages to keep
    opts  - Options map (optional)
  
  Options:
    :preserve-system - Keep all system messages? (default: true)
  
  Returns:
    Interceptor map"
  ([n]
   (sliding-window-interceptor n {}))
  ([n {:keys [preserve-system]
       :or {preserve-system true}}]
   {:name :sliding-window
    :enter (fn [ctx]
             (let [messages (:messages ctx)]
               (if preserve-system
                 (let [system-msgs (filter #(= :system (:role %)) messages)
                       other-msgs (remove #(= :system (:role %)) messages)
                       windowed (take-last n other-msgs)
                       final-messages (concat system-msgs windowed)]
                   (assoc ctx :transformed-messages final-messages))
                 (let [windowed (take-last n messages)]
                   (assoc ctx :transformed-messages windowed)))))}))

(defn preserve-system-interceptor
  "Wrapper interceptor that preserves system messages when applying another interceptor.
  
  This allows you to apply a context transformation interceptor (like token-limit
  or sliding-window) while ensuring system messages are always preserved.
  
  Arguments:
    inner-interceptor - The interceptor to wrap
  
  Returns:
    Interceptor map"
  [inner-interceptor]
  {:name :preserve-system
   :enter (fn [ctx]
            (let [messages (:messages ctx)
                  system-msgs (filter #(= :system (:role %)) messages)
                  other-msgs (remove #(= :system (:role %)) messages)
                  ;; Apply inner interceptor to non-system messages
                  inner-ctx (assoc ctx :messages other-msgs)
                  transformed-ctx (if-let [enter-fn (:enter inner-interceptor)]
                                   (enter-fn inner-ctx)
                                   inner-ctx)
                  transformed-other (or (:transformed-messages transformed-ctx)
                                       (:messages transformed-ctx))
                  final-messages (concat system-msgs transformed-other)]
              (assoc ctx :transformed-messages final-messages)))})

