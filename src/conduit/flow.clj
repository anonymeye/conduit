(ns conduit.flow
  "Pipeline composition utilities for building data transformation flows.
  
  Flows are composed of steps that transform state. Each step is a function
  that takes state and returns new state."
  (:require [conduit.core :as c]))

;; -----------------------------------------------------------------------------
;; Step Definition

(defn step
  "Create a named step in a pipeline.
  
  Arguments:
    name - Step name (keyword or string)
    f    - Function (state -> new-state)
  
  Returns:
    Step map with :name and :fn
  
  Example:
    (step :preprocess
          (fn [state]
            (assoc state :processed true)))"
  [name f]
  {:name name
   :fn f})

;; -----------------------------------------------------------------------------
;; Pipeline Execution

(defn run-pipeline
  "Execute a pipeline of steps sequentially.
  
  Arguments:
    steps - Vector of step maps or functions
    state - Initial state map
    opts  - Options:
            :on-step - Callback (fn [step-name state] ...)
            :stop?   - Predicate (fn [state] boolean) to stop early
  
  Returns:
    Final state map
  
  Example:
    (run-pipeline
      [(step :add-system (fn [s] (assoc s :messages [...])))
       (step :call-llm (fn [s] (assoc s :response (c/chat model (:messages s)))))]
      {:messages []}
      {})"
  [steps state {:keys [on-step stop?]}]
  (loop [current-state state
         remaining-steps steps]
    (if (or (empty? remaining-steps)
            (and stop? (stop? current-state)))
      current-state
      (let [step (first remaining-steps)
            step-fn (if (map? step) (:fn step) step)
            step-name (if (map? step) (:name step) :anonymous)
            new-state (step-fn current-state)]
        (when on-step
          (on-step step-name new-state))
        (recur new-state (rest remaining-steps))))))

;; -----------------------------------------------------------------------------
;; Pipeline Composition

(defn pipeline
  "Create a pipeline from steps.
  
  Arguments:
    steps - Vector of step maps or functions
  
  Returns:
    Function (fn [state opts] -> final-state)
  
  Example:
    (def my-pipeline
      (pipeline
        [(step :preprocess preprocess-fn)
         (step :call-llm call-llm-fn)
         (step :postprocess postprocess-fn)]))
    
    (my-pipeline {:input \"Hello\"} {})"
  [steps]
  (fn [state opts]
    (run-pipeline steps state opts)))

;; -----------------------------------------------------------------------------
;; Common Step Builders

(defn llm-step
  "Create a step that calls an LLM.
  
  Arguments:
    name  - Step name
    model - ChatModel instance
    opts  - Options:
            :prompt-fn   - Function (state -> messages) to build prompt
            :merge-fn    - Function (state response -> new-state) to merge response
                          (default: assoc :response)
            :chat-opts   - Options to pass to chat
            :interceptors - Vector of interceptors to apply to chat call (optional)
  
  Returns:
    Step map
  
  Example:
    (llm-step :generate
               model
               {:prompt-fn (fn [s] [{:role :user :content (:input s)}])
                :merge-fn (fn [s r] (assoc s :output (:content r)))})
    
    (llm-step :generate-with-retry
               model
               {:prompt-fn (fn [s] [{:role :user :content (:input s)}])
                :interceptors [(retry-interceptor {:max-attempts 3})
                              (logging-interceptor {:level :info})]})"
  [name model {:keys [prompt-fn merge-fn chat-opts interceptors]
               :or {merge-fn (fn [state response]
                              (assoc state :response response))}}]
  (step name
        (fn [state]
          (let [messages (prompt-fn state)
                ;; Choose chat function based on interceptors
                chat-fn (if (seq interceptors)
                         ;; Use interceptor-aware chat
                         (fn [model msgs opts]
                           (c/chat-with-interceptors model msgs interceptors opts))
                         ;; Use regular chat (backward compatible)
                         c/chat)
                response (chat-fn model messages (or chat-opts {}))]
            (merge-fn state response)))))

(defn transform-step
  "Create a step that transforms state with a function.
  
  Arguments:
    name - Step name
    f    - Function (state -> new-state)
  
  Returns:
    Step map
  
  Example:
    (transform-step :extract-content
                    (fn [s] (assoc s :text (c/extract-content (:response s)))))"
  [name f]
  (step name f))

(defn conditional-step
  "Create a step that conditionally applies a transformation.
  
  Arguments:
    name      - Step name
    predicate - Function (state -> boolean)
    step-fn   - Function (state -> new-state) to apply if predicate is true
  
  Returns:
    Step map
  
  Example:
    (conditional-step :maybe-summarize
                      (fn [s] (> (count (:messages s)) 10))
                      summarize-fn)"
  [name predicate step-fn]
  (step name
        (fn [state]
          (if (predicate state)
            (step-fn state)
            state))))

(defn parallel-step
  "Create a step that runs multiple transformations in parallel and merges results.
  
  Arguments:
    name - Step name
    fns  - Map of key to function (state -> value)
  
  Returns:
    Step map that merges all results into state
  
  Example:
    (parallel-step :gather-info
                   {:weather (fn [s] (get-weather (:location s)))
                    :news (fn [s] (get-news (:topic s)))})"
  [name fns]
  (step name
        (fn [state]
          (let [futures (into {}
                              (map (fn [[k f]]
                                     [k (future (f state))])
                                   fns))
                results (into {}
                              (map (fn [[k fut]]
                                     [k @fut])
                                   futures))]
            (merge state results)))))

;; -----------------------------------------------------------------------------
;; Pipeline Utilities

(defn compose-pipelines
  "Compose multiple pipelines into one.
  
  Arguments:
    pipelines - Vector of pipeline functions
  
  Returns:
    Composed pipeline function
  
  Example:
    (def combined (compose-pipelines [preprocessing-pipeline
                                     main-pipeline
                                     postprocessing-pipeline]))"
  [pipelines]
  (fn [state opts]
    (reduce (fn [current-state pipeline-fn]
              (pipeline-fn current-state opts))
            state
            pipelines)))

(defn branch-pipeline
  "Create a pipeline that branches based on a predicate.
  
  Arguments:
    predicate - Function (state -> keyword) returning branch name
    branches  - Map of branch name to pipeline function
  
  Returns:
    Pipeline function
  
  Example:
    (branch-pipeline
      (fn [s] (:type s))
      {:question qa-pipeline
       :task task-pipeline
       :default default-pipeline})"
  [predicate branches]
  (fn [state opts]
    (let [branch-name (predicate state)
          pipeline-fn (get branches branch-name (get branches :default identity))]
      (pipeline-fn state opts))))

(defn loop-pipeline
  "Create a pipeline that loops until a condition is met.
  
  Arguments:
    pipeline-fn - Pipeline function to execute
    stop?       - Predicate (state -> boolean) to stop looping
    max-iter    - Maximum iterations (default: 10)
  
  Returns:
    Pipeline function
  
  Example:
    (loop-pipeline
      my-pipeline
      (fn [s] (not (:done s)))
      :max-iter 5)"
  [pipeline-fn stop? & {:keys [max-iter] :or {max-iter 10}}]
  (fn [state opts]
    (loop [current-state state
           iteration 0]
      (if (or (>= iteration max-iter)
              (stop? current-state))
        current-state
        (recur (pipeline-fn current-state opts)
               (inc iteration))))))

