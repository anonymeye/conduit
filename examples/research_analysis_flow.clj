(ns examples.research-analysis-flow
  "Complex example demonstrating conduit.flow capabilities.
  
  This example shows a research paper analysis pipeline that:
  1. Preprocesses and validates input
  2. Extracts metadata in parallel
  3. Performs multiple analyses concurrently
  4. Conditionally processes based on content
  5. Generates a comprehensive report
  6. Validates and formats output
  
  Demonstrates: llm-step, transform-step, conditional-step, parallel-step,
  pipeline composition, and state threading."
  (:require [conduit.core :as c]
            [conduit.flow :as flow]
            [conduit.providers.grok :as grok]))

;; ============================================================================
;; Example: Research Paper Analysis Pipeline
;; ============================================================================

;; Assume we have a model instance
(def model (grok/model {:model "grok-3"}))

;; ----------------------------------------------------------------------------
;; Step 1: Preprocessing and Validation
;; ----------------------------------------------------------------------------

(defn validate-input-step
  "Validate that input document exists and has required fields."
  []
  (flow/transform-step
   :validate-input
   (fn [state]
     (cond
       (not (:document state))
       (assoc state :error "Missing :document field" :valid? false)
       
       (not (string? (:document state)))
       (assoc state :error "Document must be a string" :valid? false)
       
       (< (count (:document state)) 100)
       (assoc state :error "Document too short (minimum 100 chars)" :valid? false)
       
       :else
       (assoc state :valid? true
              :document-length (count (:document state))
              :timestamp (System/currentTimeMillis))))))

(defn enrich-metadata-step
  "Add metadata about the document."
  []
  (flow/transform-step
   :enrich-metadata
   (fn [state]
     (let [doc (:document state)
           word-count (count (re-seq #"\w+" doc))
           paragraph-count (count (re-seq #"\n\n+" doc))
           has-citations (boolean (re-find #"\[.*?\]|\(.*?\d{4}.*?\)" doc))]
       (assoc state
              :metadata {:word-count word-count
                        :paragraph-count paragraph-count
                        :has-citations has-citations
                        :estimated-reading-time (int (/ word-count 200))})))))

;; ----------------------------------------------------------------------------
;; Step 2: Parallel Analysis (Multiple LLM calls in parallel)
;; ----------------------------------------------------------------------------

(defn parallel-analysis-step
  "Run multiple analyses in parallel using the LLM."
  []
  (flow/parallel-step
   :parallel-analysis
   {:extract-key-points
    (fn [state]
      (let [response (c/chat model
                             [{:role :system
                               :content "You are a research analyst. Extract the 5 most important key points from the document."}
                              {:role :user
                               :content (str "Document:\n\n" (:document state))}]
                             {:max-tokens 500})]
        {:key-points (c/extract-content response)}))
    
    :identify-topics
    (fn [state]
      (let [response (c/chat model
                             [{:role :system
                               :content "You are a topic classifier. Identify the main topics and research areas."}
                              {:role :user
                               :content (str "Document:\n\n" (:document state))}]
                             {:max-tokens 300
                              :response-format :json})]
        {:topics (c/extract-content response)}))
    
    :assess-methodology
    (fn [state]
      (let [response (c/chat model
                             [{:role :system
                               :content "You are a research methodology expert. Assess the research methodology and approach."}
                              {:role :user
                               :content (str "Document:\n\n" (:document state))}]
                             {:max-tokens 400})]
        {:methodology (c/extract-content response)}))
    
    :evaluate-strengths-weaknesses
    (fn [state]
      (let [response (c/chat model
                             [{:role :system
                               :content "You are a critical reviewer. Identify strengths and weaknesses."}
                              {:role :user
                               :content (str "Document:\n\n" (:document state))}]
                             {:max-tokens 400})]
        {:strengths-weaknesses (c/extract-content response)}))}))

;; ----------------------------------------------------------------------------
;; Step 3: Conditional Processing
;; ----------------------------------------------------------------------------

(defn conditional-deep-analysis-step
  "Perform deep analysis only if document is long enough or has citations."
  []
  (flow/conditional-step
   :conditional-deep-analysis
   ;; Predicate: should we do deep analysis?
   (fn [state]
     (let [metadata (:metadata state)]
       (or (> (:word-count metadata) 2000)
           (:has-citations metadata))))
   
   ;; Deep analysis function
   (fn [state]
     (let [response (c/chat model
                            [{:role :system
                              :content "You are a research expert. Provide a deep critical analysis including:\n- Research quality assessment\n- Contribution to the field\n- Potential limitations\n- Future research directions"}
                             {:role :user
                              :content (str "Based on this document:\n\n" (:document state)
                                          "\n\nPrevious analyses:\n"
                                          "- Key Points: " (:key-points state) "\n"
                                          "- Topics: " (:topics state) "\n"
                                          "- Methodology: " (:methodology state))}]
                            {:max-tokens 800})]
       (assoc state :deep-analysis (c/extract-content response)
                    :deep-analysis-performed? true)))))

;; ----------------------------------------------------------------------------
;; Step 4: Generate Comprehensive Report
;; ----------------------------------------------------------------------------

(defn generate-report-step
  "Generate a comprehensive analysis report."
  []
  (flow/llm-step
   :generate-report
   model
   {:prompt-fn
    (fn [state]
      [{:role :system
        :content "You are a research report writer. Create a comprehensive, well-structured analysis report."}
       {:role :user
        :content (str "Create a comprehensive research analysis report based on the following:\n\n"
                     "Document Metadata:\n"
                     "- Word Count: " (get-in state [:metadata :word-count]) "\n"
                     "- Paragraphs: " (get-in state [:metadata :paragraph-count]) "\n"
                     "- Has Citations: " (get-in state [:metadata :has-citations]) "\n\n"
                     "Key Points:\n" (:key-points state) "\n\n"
                     "Topics: " (:topics state) "\n\n"
                     "Methodology: " (:methodology state) "\n\n"
                     "Strengths & Weaknesses: " (:strengths-weaknesses state) "\n"
                     (when (:deep-analysis state)
                       (str "\nDeep Analysis: " (:deep-analysis state) "\n"))
                     "\n\nFormat the report with clear sections and professional language.")}])
    
    :merge-fn
    (fn [state response]
      (assoc state :final-report (c/extract-content response)
                   :report-generated-at (System/currentTimeMillis)))}))

;; ----------------------------------------------------------------------------
;; Step 5: Post-processing and Validation
;; ----------------------------------------------------------------------------

(defn extract-report-sections-step
  "Extract structured sections from the report."
  []
  (flow/transform-step
   :extract-sections
   (fn [state]
     (let [report (:final-report state)
           ;; Simple regex-based extraction (in real app, use LLM or NLP)
           summary (or (second (re-find #"(?i)summary[:\s]+(.*?)(?=\n\n|\n[A-Z])" report))
                       "Not found")
           conclusions (or (second (re-find #"(?i)conclusion[:\s]+(.*?)(?=\n\n|\n[A-Z])" report))
                          "Not found")]
       (assoc state
              :report-sections {:summary summary
                                :conclusions conclusions
                                :full-report report})))))

(defn calculate-quality-score-step
  "Calculate a quality score based on various factors."
  []
  (flow/transform-step
   :calculate-quality-score
   (fn [state]
     (let [metadata (:metadata state)
           score (atom 0)]
       ;; Word count score (0-20 points)
       (when (> (:word-count metadata) 1000)
         (swap! score + 10))
       (when (> (:word-count metadata) 2000)
         (swap! score + 10))
       
       ;; Citations score (0-15 points)
       (when (:has-citations metadata)
         (swap! score + 15))
       
       ;; Analysis completeness score (0-30 points)
       (when (:key-points state) (swap! score + 7))
       (when (:topics state) (swap! score + 7))
       (when (:methodology state) (swap! score + 8))
       (when (:strengths-weaknesses state) (swap! score + 8))
       
       ;; Deep analysis bonus (0-25 points)
       (when (:deep-analysis-performed? state)
         (swap! score + 25))
       
       ;; Report quality (0-10 points)
       (when (:final-report state)
         (let [report-length (count (:final-report state))]
           (when (> report-length 500) (swap! score + 5))
           (when (> report-length 1000) (swap! score + 5))))
       
       (assoc state :quality-score @score
                    :quality-grade (cond
                                    (>= @score 80) "A"
                                    (>= @score 65) "B"
                                    (>= @score 50) "C"
                                    (>= @score 35) "D"
                                    :else "F"))))))

(defn finalize-output-step
  "Prepare final output structure."
  []
  (flow/transform-step
   :finalize-output
   (fn [state]
     {:status :success
      :analysis-id (str (java.util.UUID/randomUUID))
      :timestamp (:timestamp state)
      :document-info {:length (:document-length state)
                      :metadata (:metadata state)}
      :analyses {:key-points (:key-points state)
                 :topics (:topics state)
                 :methodology (:methodology state)
                 :strengths-weaknesses (:strengths-weaknesses state)
                 :deep-analysis (:deep-analysis state)}
      :report (:report-sections state)
      :quality {:score (:quality-score state)
                :grade (:quality-grade state)}
      :processing-info {:deep-analysis-performed? (:deep-analysis-performed? state)
                        :report-generated-at (:report-generated-at state)}})))

;; ----------------------------------------------------------------------------
;; Pipeline Composition
;; ----------------------------------------------------------------------------

(def preprocessing-pipeline
  "Preprocessing and validation steps."
  (flow/pipeline
   [(validate-input-step)
    (enrich-metadata-step)]))

(def analysis-pipeline
  "Analysis steps."
  (flow/pipeline
   [(parallel-analysis-step)
    (conditional-deep-analysis-step)]))

(def reporting-pipeline
  "Report generation and finalization."
  (flow/pipeline
   [(generate-report-step)
    (extract-report-sections-step)
    (calculate-quality-score-step)
    (finalize-output-step)]))

;; Main pipeline: compose all sub-pipelines
(def research-analysis-pipeline
  "Complete research paper analysis pipeline."
  (flow/compose-pipelines
   [preprocessing-pipeline
    analysis-pipeline
    reporting-pipeline]))

;; ----------------------------------------------------------------------------
;; Usage Example
;; ----------------------------------------------------------------------------

(comment
  ;; Example document
  (def sample-document
    "Artificial Intelligence and Machine Learning: A Comprehensive Review
    
    Abstract:
    This paper provides a comprehensive review of artificial intelligence (AI) 
    and machine learning (ML) technologies, their applications, and future 
    directions. We examine the historical development of AI, current state 
    of the art, and emerging trends.
    
    Introduction:
    Artificial intelligence has evolved significantly since its inception in 
    the 1950s. Modern AI systems leverage machine learning algorithms to 
    process vast amounts of data and make intelligent decisions.
    
    Methodology:
    Our review methodology involved systematic analysis of peer-reviewed 
    publications from 2010-2024, focusing on breakthrough technologies and 
    practical applications. We analyzed over 500 papers from top-tier 
    conferences and journals.
    
    Key Findings:
    1. Deep learning has revolutionized computer vision and natural language 
       processing
    2. Transfer learning enables efficient model training with limited data
    3. Explainable AI is becoming increasingly important for critical applications
    4. Edge AI deployment is growing rapidly
    5. Ethical considerations are shaping AI development
    
    Conclusion:
    AI and ML continue to transform industries and society. Future research 
    should focus on improving model interpretability, reducing computational 
    requirements, and addressing ethical concerns.
    
    References:
    [1] LeCun, Y., Bengio, Y., & Hinton, G. (2015). Deep learning. Nature.
    [2] Vaswani, A., et al. (2017). Attention is all you need. NeurIPS.
    [3] Bender, E. M., et al. (2021). On the dangers of stochastic parrots.")
  
  ;; Run the pipeline
  (def result
    (research-analysis-pipeline
     {:document sample-document}
     {:on-step (fn [step-name state]
                 (println "Step:" step-name
                         "| Valid:" (:valid? state)
                         "| Has Report:" (boolean (:final-report state))))}))
  
  ;; Inspect results
  (println "Quality Score:" (get-in result [:quality :score]))
  (println "Quality Grade:" (get-in result [:quality :grade]))
  (println "Key Points:" (get-in result [:analyses :key-points]))
  (println "Report Summary:" (get-in result [:report :summary]))
  
  ;; Example with error handling
  (def bad-result
    (research-analysis-pipeline
     {:document "Too short"}  ; Will fail validation
     {}))
  
  ;; Check for errors
  (if (:error bad-result)
    (println "Error:" (:error bad-result))
    (println "Success!"))
  
  ;; Example with conditional branching
  (def short-doc-result
    (research-analysis-pipeline
     {:document (apply str (repeat 150 "word "))}  ; Short document, no deep analysis
     {}))
  
  ;; Verify deep analysis was skipped
  (println "Deep analysis performed?"
          (get-in short-doc-result [:processing-info :deep-analysis-performed?]))
  )

;; ----------------------------------------------------------------------------
;; Advanced: Pipeline with Branching
;; ----------------------------------------------------------------------------

(defn document-type-router
  "Route to different pipelines based on document type."
  [state]
  (let [doc (:document state)]
    (cond
      (or (re-find #"(?i)\b(abstract|introduction|methodology|conclusion)\b" doc)
          (re-find #"(?i)\breferences?\b" doc))
      :research-paper
      
      (re-find #"(?i)\b(code|function|algorithm|implementation)\b" doc)
      :technical-document
      
      (re-find #"(?i)\b(business|market|revenue|strategy)\b" doc)
      :business-document
      
      :else
      :general)))

(def research-paper-pipeline research-analysis-pipeline)

(def technical-document-pipeline
  (flow/pipeline
   [(validate-input-step)
    (enrich-metadata-step)
    (flow/llm-step :analyze-code model
                   {:prompt-fn (fn [s] [{:role :user
                                        :content (str "Analyze this technical document:\n\n" (:document s))}])
                    :merge-fn (fn [s r] (assoc s :code-analysis (c/extract-content r)))})
    (finalize-output-step)]))

(def business-document-pipeline
  (flow/pipeline
   [(validate-input-step)
    (enrich-metadata-step)
    (flow/llm-step :analyze-business model
                   {:prompt-fn (fn [s] [{:role :user
                                        :content (str "Analyze this business document:\n\n" (:document s))}])
                    :merge-fn (fn [s r] (assoc s :business-analysis (c/extract-content r)))})
    (finalize-output-step)]))

(def adaptive-pipeline
  "Pipeline that branches based on document type."
  (flow/branch-pipeline
   document-type-router
   {:research-paper research-paper-pipeline
    :technical-document technical-document-pipeline
    :business-document business-document-pipeline
    :default (flow/pipeline [(validate-input-step) (enrich-metadata-step) (finalize-output-step)])}))

(comment
  ;; Use adaptive pipeline
  (def adaptive-result
    (adaptive-pipeline
     {:document sample-document}  ; Will route to research-paper-pipeline
     {}))
  )

