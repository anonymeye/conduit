(ns conduit.interceptor
  "Core interceptor pattern implementation.
  
  Interceptors provide a data-driven, composable way to process requests through
  a pipeline. Inspired by Pedestal's interceptor pattern.
  
  An interceptor is a map with optional functions:
  - :name      - Identifier (keyword or string)
  - :enter     - (fn [context] context') - Called before processing
  - :leave     - (fn [context] context') - Called after processing  
  - :error     - (fn [context error] context') - Called on error
  
  Context is a map containing:
  - :model              - ChatModel instance
  - :messages           - Input messages
  - :opts               - Chat options
  - :response           - Response (set after model call)
  - :queue              - Remaining interceptors to execute
  - :stack              - Completed interceptors (for leave phase)
  - :error              - Any error that occurred
  - :terminated?        - Flag for early termination
  - :transformed-messages - Modified messages (optional)
  - :transformed-opts   - Modified opts (optional)
  - :metadata           - Interceptor-shared data (optional)
  
  Execution model:
  1. Execute :enter functions (forward through queue)
  2. Model call happens outside interceptor chain
  3. Execute :leave functions (backward through stack)
  4. Handle errors via :error functions")

;;; -----------------------------------------------------------------------------
;;; Core Data Structures

(defn interceptor
  "Create an interceptor from various forms.
  
  Can be called with:
  - A map containing :enter, :leave, :error, :name keys
  - A single function (treated as :enter)
  - Named arguments (:enter fn, :leave fn, etc.)
  
  Examples:
    (interceptor {:name :my-int :enter (fn [ctx] ctx)})
    (interceptor (fn [ctx] ctx))
    (interceptor :name :my-int :enter (fn [ctx] ctx) :leave (fn [ctx] ctx))"
  ([x]
   (cond
     ;; Already an interceptor map
     (map? x)
     (do
       (assert (or (:enter x) (:leave x) (:error x))
               "Interceptor must have at least one of :enter, :leave, or :error")
       x)
     
     ;; Single function - treat as :enter
     (fn? x)
     {:enter x}
     
     :else
     (throw (ex-info "Invalid interceptor form"
                     {:form x
                      :valid-forms [:map :function]}))))
  
  ([k v & {:as kvs}]
   (let [m (assoc kvs k v)]
     (interceptor m))))

(defn context
  "Create an initial context map.
  
  Required keys:
  - :model    - ChatModel instance
  - :messages - Vector of messages
  
  Optional keys:
  - :opts     - Chat options (default: {})
  - :queue    - Initial interceptor queue (default: [])
  - Any other keys for custom data"
  [{:keys [model messages opts queue] :as ctx-map}]
  (assert model ":model is required")
  (assert messages ":messages is required")
  (merge
   {:model model
    :messages messages
    :opts (or opts {})
    :queue (or queue [])
    :stack []
    :response nil
    :error nil
    :terminated? false}
   (dissoc ctx-map :model :messages :opts :queue)))

;;; -----------------------------------------------------------------------------
;;; Execution Control

(defn terminate
  "Mark context for early termination of interceptor chain.
  
  When terminated, no further interceptors in the queue will be executed.
  The leave phase will still execute for interceptors already in the stack."
  [ctx]
  (assoc ctx :terminated? true))

(defn terminated?
  "Check if context has been marked for termination."
  [ctx]
  (boolean (:terminated? ctx)))

(defn enqueue
  "Add interceptors to the end of the queue.
  
  Interceptors can be:
  - A single interceptor
  - A collection of interceptors
  - Variable arguments of interceptors"
  ([ctx interceptor]
   (update ctx :queue conj interceptor))
  
  ([ctx interceptor & interceptors]
   (update ctx :queue into (cons interceptor interceptors))))

(defn enqueue-before
  "Add interceptors to the front of the queue.
  
  These will be executed before any currently queued interceptors."
  [ctx & interceptors]
  (update ctx :queue #(into (vec interceptors) %)))

(defn error
  "Add an error to the context.
  
  Setting an error will:
  1. Stop processing the enter phase
  2. Trigger error handlers in reverse order
  3. If no error handler clears the error, it remains in context"
  [ctx err]
  (assoc ctx :error err))

(defn clear-error
  "Remove error from context, allowing processing to continue."
  [ctx]
  (dissoc ctx :error))

;;; -----------------------------------------------------------------------------
;;; Enter Phase Execution

(defn- execute-enter-phase
  "Execute the enter phase of interceptors.
  
  Processes interceptors from the queue in forward order:
  1. Removes interceptor from queue
  2. Adds it to stack
  3. Executes its :enter function
  4. Continues with next interceptor
  
  Stops if:
  - Queue is empty
  - Context is terminated
  - An error occurs"
  [ctx]
  (loop [current-ctx ctx]
    (cond
      ;; Stop conditions
      (or (empty? (:queue current-ctx))
          (:terminated? current-ctx)
          (:error current-ctx))
      current-ctx
      
      ;; Process next interceptor
      :else
      (let [interceptor (first (:queue current-ctx))
            remaining (vec (rest (:queue current-ctx)))
            next-ctx (-> current-ctx
                        (assoc :queue remaining)
                        (update :stack conj interceptor))
            ;; Execute enter function with error handling
            result-ctx (try
                        (if-let [enter-fn (:enter interceptor)]
                          (let [result (enter-fn next-ctx)]
                            (assert (map? result)
                                    (str "Interceptor :enter must return context map. "
                                         "Got: " (type result)))
                            result)
                          next-ctx)
                        (catch Exception e
                          ;; Add error and stop enter phase
                          (assoc next-ctx :error e)))]
        (recur result-ctx)))))

;;; -----------------------------------------------------------------------------
;;; Leave Phase Execution

(defn- execute-leave-phase
  "Execute the leave phase of interceptors.
  
  Processes interceptors from the stack in reverse order:
  1. Removes interceptor from stack
  2. Executes its :leave function
  3. Continues with next interceptor
  
  Stops when stack is empty."
  [ctx]
  (loop [current-ctx ctx]
    (if (empty? (:stack current-ctx))
      current-ctx
      
      (let [interceptor (peek (:stack current-ctx))
            remaining (pop (:stack current-ctx))
            next-ctx (assoc current-ctx :stack remaining)
            ;; Execute leave function with error handling
            result-ctx (try
                        (if-let [leave-fn (:leave interceptor)]
                          (let [result (leave-fn next-ctx)]
                            (assert (map? result)
                                    (str "Interceptor :leave must return context map. "
                                         "Got: " (type result)))
                            result)
                          next-ctx)
                        (catch Exception e
                          ;; Add error but continue processing
                          (assoc next-ctx :error e)))]
        (recur result-ctx)))))

;;; -----------------------------------------------------------------------------
;;; Error Phase Execution

(defn- execute-error-phase
  "Execute error handlers in reverse order through the stack.
  
  When an error occurs:
  1. Processes interceptors from stack in reverse order
  2. Calls :error function if present
  3. If error handler clears the error, processing can continue
  4. If error remains after all handlers, it stays in context"
  [ctx]
  (loop [current-ctx ctx]
    (cond
      ;; No more error handlers or error was cleared
      (or (empty? (:stack current-ctx))
          (nil? (:error current-ctx)))
      current-ctx
      
      ;; Try next error handler
      :else
      (let [interceptor (peek (:stack current-ctx))
            remaining (pop (:stack current-ctx))
            next-ctx (assoc current-ctx :stack remaining)
            ;; Execute error handler if present
            result-ctx (if-let [error-fn (:error interceptor)]
                        (try
                          (let [result (error-fn next-ctx (:error current-ctx))]
                            (assert (map? result)
                                    (str "Interceptor :error must return context map. "
                                         "Got: " (type result)))
                            result)
                          (catch Exception e
                            ;; Error in error handler - replace original error
                            (assoc next-ctx :error e)))
                        ;; No error handler - continue
                        next-ctx)]
        (recur result-ctx)))))

;;; -----------------------------------------------------------------------------
;;; Main Execution

(defn execute
  "Execute interceptor chain through enter phase only.
  
  This executes the :enter functions of queued interceptors.
  Used before making the model call.
  
  Returns context after enter phase with:
  - Empty :queue (all processed)
  - Populated :stack (for leave phase)
  - Possibly :error if one occurred
  - Possibly :terminated? if chain was terminated"
  [ctx]
  (let [after-enter (execute-enter-phase ctx)]
    ;; If error occurred during enter, handle it
    (if (:error after-enter)
      (execute-error-phase after-enter)
      after-enter)))

(defn execute-leave
  "Execute interceptor chain through leave phase only.
  
  This executes the :leave functions of stacked interceptors.
  Used after making the model call.
  
  Returns context after leave phase with:
  - Empty :stack (all processed)
  - Possibly :error if one occurred"
  [ctx]
  ;; If there's an error before leave phase (e.g., from model call), handle it first
  (let [ctx-to-process (if (:error ctx)
                         (execute-error-phase ctx)
                         ctx)
        after-leave (execute-leave-phase ctx-to-process)]
    ;; If error occurred during leave, handle it
    (if (:error after-leave)
      (execute-error-phase after-leave)
      after-leave)))

(defn execute-all
  "Execute complete interceptor chain: enter, leave, and error phases.
  
  This is primarily for testing or special cases where you want to execute
  both phases without a model call in between.
  
  Normal usage would be:
  1. execute (enter phase)
  2. Make model call
  3. execute-leave (leave phase)"
  [ctx]
  (-> ctx
      execute
      execute-leave))

;;; -----------------------------------------------------------------------------
;;; Utilities

(defn chain
  "Create an interceptor queue from a collection of interceptor definitions.
  
  Each element can be:
  - An interceptor map
  - A function (converted to :enter interceptor)
  - A collection (flattened recursively)
  
  Returns a vector of interceptors."
  [& interceptor-defs]
  (vec
   (mapcat
    (fn [def]
      (cond
        (nil? def) []
        (sequential? def) (apply chain def)
        :else [(interceptor def)]))
    interceptor-defs)))

(defn interceptor?
  "Check if x is a valid interceptor."
  [x]
  (and (map? x)
       (or (:enter x) (:leave x) (:error x))))

(defn describe-interceptor
  "Get a human-readable description of an interceptor."
  [int]
  (when (interceptor? int)
    (let [name (or (:name int) "anonymous")
          phases (cond-> []
                   (:enter int) (conj :enter)
                   (:leave int) (conj :leave)
                   (:error int) (conj :error))]
      {:name name
       :phases phases})))

(defn describe-context
  "Get a human-readable summary of context state."
  [ctx]
  {:queue-size (count (:queue ctx))
   :stack-size (count (:stack ctx))
   :has-error? (some? (:error ctx))
   :terminated? (:terminated? ctx)
   :has-response? (some? (:response ctx))
   :message-count (count (:messages ctx))})

(comment
  ;; Example usage
  
  ;; Simple interceptor
  (def logging-int
    (interceptor
     {:name :logging
      :enter (fn [ctx]
               (println "Enter:" (count (:messages ctx)) "messages")
               ctx)
      :leave (fn [ctx]
               (println "Leave: response=" (:response ctx))
               ctx)}))
  
  ;; Interceptor from function
  (def simple-int
    (interceptor
     (fn [ctx]
       (println "Processing...")
       ctx)))
  
  ;; Execute chain
  (def ctx
    (context {:model :mock-model
              :messages [{:role :user :content "Hi"}]
              :queue [(interceptor {:name :int-1 :enter #(do (println "Int 1") %)})
                      (interceptor {:name :int-2 :enter #(do (println "Int 2") %)})]}))
  
  (-> ctx
      execute
      (assoc :response {:content "Hello!"})
      execute-leave)
  
  ;; Early termination
  (def cache-int
    (interceptor
     {:name :cache
      :enter (fn [ctx]
               ;; Simulating cache lookup
               (let [cache-store (atom {})
                     cached (get @cache-store (:messages ctx))]
                 (if cached
                   (-> ctx
                       (assoc :response cached)
                       terminate)
                   ctx)))}))
  
  ;; Error handling
  (def retry-int
    (interceptor
     {:name :retry
      :error (fn [ctx _error]
               (if (< (:retry-count ctx 0) 3)
                 (-> ctx
                     (update :retry-count (fnil inc 0))
                     clear-error)
                 ctx))})))

