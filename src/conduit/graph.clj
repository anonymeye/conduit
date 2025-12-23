(ns conduit.graph
  "Stateful workflow graphs for complex agent workflows.
  
  Graphs support state machines, conditional routing, and explicit control flow.
  Nodes are pure functions that transform state, edges route between nodes."
  (:require [conduit.core :as c]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Graph Definition

(defn graph
  "Create a graph definition.
  
  Arguments:
    nodes - Map of node name (keyword) to node function (state -> new-state)
    edges - Map of node name to:
            - Keyword (fixed transition)
            - Function (state -> next-node-keyword) for conditional routing
            - :end (terminal node)
  
  Returns:
    Graph map with :nodes and :edges
  
  Example:
    (graph
      {:classify classify-fn
       :handle-qa handle-qa-fn
       :respond respond-fn}
      {:start :classify
       :classify (fn [state]
                  (case (:intent state)
                    \"question\" :handle-qa
                    :respond))
       :handle-qa :respond
       :respond :end})"
  [nodes edges]
  {:nodes nodes
   :edges edges})

;; -----------------------------------------------------------------------------
;; Graph Execution

(defn run
  "Execute a graph from start to end.
  
  Arguments:
    graph - Graph definition map
    state - Initial state map
    opts  - Options:
            :max-steps    - Maximum steps (default: 100)
            :on-enter     - Called when entering a node (fn [node-name state] ...)
            :on-exit      - Called when exiting a node (fn [node-name state] ...)
            :on-transition - Called on edge transition (fn [from to state] ...)
  
  Returns:
    Final state map
  
  Throws:
    ExceptionInfo with :type :max-steps if max steps exceeded
    ExceptionInfo if unknown node or invalid edge
  
  Example:
    (run my-graph
         {:messages [{:role :user :content \"Hello\"}]}
         {:max-steps 50})"
  [graph state {:keys [max-steps on-enter on-exit on-transition]
                :or {max-steps 100}}]
  (let [{:keys [nodes edges]} graph]
    (loop [current-node :start
           current-state state
           step 0]
      
      ;; Check max steps
      (when (>= step max-steps)
        (throw (ex-info "Max steps exceeded"
                        {:type :max-steps
                         :step step
                         :node current-node
                         :state current-state})))
      
      ;; Get next node from edges
      (let [edge (get edges current-node)
            next-node (cond
                        (nil? edge) :end
                        (= edge :end) :end
                        (keyword? edge) edge
                        (fn? edge) (edge current-state)
                        :else (throw (ex-info "Invalid edge"
                                               {:node current-node
                                                :edge edge})))]
        
        (when on-transition
          (on-transition {:from current-node :to next-node :state current-state}))
        
        (if (= next-node :end)
          ;; Terminal - return final state
          current-state
          
          ;; Execute next node
          (let [node-fn (get nodes next-node)]
            (when-not node-fn
              (throw (ex-info "Unknown node"
                              {:node next-node
                               :available (keys nodes)})))
            
            (when on-enter
              (on-enter {:node next-node :state current-state}))
            
            (let [new-state (node-fn current-state)]
              (when on-exit
                (on-exit {:node next-node :state new-state}))
              
              (recur next-node new-state (inc step)))))))))

;; -----------------------------------------------------------------------------
;; Parallel Execution

(defn parallel-node
  "Create a node that runs multiple functions in parallel.
  
  Arguments:
    fns - Map of key to function (state -> value or state-update-map)
  
  Returns:
    Node function that merges all results into state
  
  Example:
    (parallel-node
      {:weather (fn [state] {:weather (get-weather (:location state))})
       :news (fn [state] {:news (get-news (:topic state))})})"
  [fns]
  (fn [state]
    (let [futures (into {}
                        (map (fn [[k f]]
                               [k (future (f state))])
                             fns))
          results (into {}
                        (map (fn [[k fut]]
                               [k @fut])
                             futures))]
      (merge state results))))

;; -----------------------------------------------------------------------------
;; Subgraphs

(defn subgraph
  "Create a node that runs a subgraph.
  
  Arguments:
    sub-graph - Graph definition
    opts      - Options:
                :state-key - Key to store subgraph state (optional)
                :graph-opts - Options to pass to graph/run
  
  Returns:
    Node function
  
  Example:
    (subgraph sub-workflow-graph
              {:state-key :sub-result})"
  [sub-graph & {:keys [state-key graph-opts]}]
  (fn [state]
    (let [result (run sub-graph state (or graph-opts {}))]
      (if state-key
        (assoc state state-key result)
        (merge state result)))))

;; -----------------------------------------------------------------------------
;; Checkpointing

(defn run-with-checkpoints
  "Run graph with checkpointing support.
  
  Arguments:
    graph - Graph definition
    state - Initial state (or checkpoint)
    opts  - Options:
            :checkpoint-fn - Called after each node with (fn [node-name state] ...)
            :resume-from   - Node to resume from (keyword)
            :max-steps     - Maximum steps (default: 100)
            :on-enter      - Called when entering a node
            :on-exit       - Called when exiting a node
            :on-transition - Called on edge transition
  
  Returns:
    Final state map
  
  Example:
    (run-with-checkpoints my-graph initial-state
      {:checkpoint-fn (fn [node state]
                       (swap! checkpoints assoc node state))})"
  [graph state {:keys [checkpoint-fn] :as opts}]
  (let [;; Override on-exit to include checkpointing
        on-exit (fn [{:keys [node state]}]
                  (when checkpoint-fn
                    (checkpoint-fn node state))
                  (when-let [original-on-exit (:on-exit opts)]
                    (original-on-exit {:node node :state state})))]
    (run graph state
         (assoc opts
                :on-exit on-exit))))

;; -----------------------------------------------------------------------------
;; Visualization

(defn graph->mermaid
  "Generate Mermaid diagram from graph definition.
  
  Arguments:
    graph - Graph definition map
  
  Returns:
    Mermaid diagram string
  
  Example:
    (println (graph->mermaid my-graph))
    ;; graph TD
    ;;     start --> classify
    ;;     classify --> classify_decision{classify?}
    ;;     question --> end"
  [{:keys [nodes edges]}]
  (let [lines (atom ["graph TD"])]
    (doseq [[from to] edges]
      (let [to-str (cond
                    (= to :end) "end"
                    (fn? to) (str (name from) "_decision{" (name from) "?}")
                    :else (name to))]
        (swap! lines conj (str "    " (name from) " --> " to-str))))
    (str/join "\n" @lines)))

;; -----------------------------------------------------------------------------
;; Graph Utilities

(defn validate-graph
  "Validate graph structure.
  
  Arguments:
    graph - Graph definition
  
  Returns:
    true if valid
  
  Throws:
    ExceptionInfo if invalid"
  [{:keys [nodes edges]}]
  (let [node-keys (set (keys nodes))
        edge-targets (set (mapcat (fn [[_ target]]
                                    (if (fn? target)
                                      []
                                      (if (= target :end)
                                        [:end]
                                        [target])))
                                  edges))]
    ;; Check that all edge targets are valid nodes or :end
    (doseq [target edge-targets]
      (when (and (not= target :end)
                 (not (contains? node-keys target)))
        (throw (ex-info "Edge targets unknown node"
                        {:target target
                         :available-nodes node-keys}))))
    true))

(defn get-reachable-nodes
  "Get all nodes reachable from :start.
  
  Arguments:
    graph - Graph definition
  
  Returns:
    Set of reachable node keywords"
  [{:keys [edges]}]
  (let [visited (atom #{})
        visit (fn visit [node]
                (when (and (not (contains? @visited node))
                           (contains? (set (keys edges)) node))
                  (swap! visited conj node)
                  (let [edge (get edges node)]
                    (when (keyword? edge)
                      (visit edge))
                    (when (fn? edge)
                      ;; For function edges, we can't statically determine targets
                      ;; but we mark the node as visited
                      nil))))]
    (visit :start)
    @visited))

