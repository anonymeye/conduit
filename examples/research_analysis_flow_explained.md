# Complex Flow Example: Research Paper Analysis Pipeline

This document explains the complex `conduit.flow` example step-by-step.

## Overview

The example demonstrates a **research paper analysis pipeline** that:
1. Validates and preprocesses input
2. Runs multiple LLM analyses in parallel
3. Conditionally performs deep analysis
4. Generates a comprehensive report
5. Calculates quality scores
6. Produces structured output

## State Flow Through Pipeline

The pipeline transforms state from:
```clojure
{:document "..."}  ; Initial state
```

To:
```clojure
{:status :success
 :analysis-id "..."
 :document-info {...}
 :analyses {...}
 :report {...}
 :quality {:score 85 :grade "A"}}
```

## Step-by-Step Breakdown

### Phase 1: Preprocessing Pipeline

#### Step 1: `validate-input-step`
```clojure
(flow/transform-step :validate-input ...)
```
- **Type**: Transform step (pure function)
- **Purpose**: Validates input document
- **State transformation**:
  ```clojure
  {:document "..."} 
  → 
  {:document "..." 
   :valid? true 
   :document-length 1234 
   :timestamp 1234567890}
  ```
- **Key concept**: Transform steps are pure functions that take state and return new state

#### Step 2: `enrich-metadata-step`
```clojure
(flow/transform-step :enrich-metadata ...)
```
- **Type**: Transform step
- **Purpose**: Adds metadata (word count, paragraphs, citations)
- **State transformation**:
  ```clojure
  {:document "..." :valid? true}
  →
  {:document "..." 
   :valid? true
   :metadata {:word-count 2500 
              :paragraph-count 8 
              :has-citations true}}
  ```

### Phase 2: Analysis Pipeline

#### Step 3: `parallel-analysis-step`
```clojure
(flow/parallel-step :parallel-analysis {...})
```
- **Type**: Parallel step
- **Purpose**: Runs 4 LLM analyses concurrently
- **Key concept**: All 4 functions run in parallel using `future`, then results are merged
- **State transformation**:
  ```clojure
  {:document "..." :metadata {...}}
  →
  {:document "..."
   :metadata {...}
   :key-points "1. ... 2. ..."
   :topics "AI, Machine Learning, ..."
   :methodology "Systematic review..."
   :strengths-weaknesses "Strengths: ..."}
  ```
- **Performance**: 4x faster than sequential execution!

#### Step 4: `conditional-deep-analysis-step`
```clojure
(flow/conditional-step :conditional-deep-analysis 
                       predicate-fn 
                       deep-analysis-fn)
```
- **Type**: Conditional step
- **Purpose**: Only runs deep analysis if document is long or has citations
- **Predicate**: `(fn [state] (> word-count 2000) OR has-citations)`
- **State transformation**:
  ```clojure
  ;; If predicate is true:
  {:key-points "..." :topics "..."}
  →
  {:key-points "..."
   :topics "..."
   :deep-analysis "Critical analysis..."
   :deep-analysis-performed? true}
  
  ;; If predicate is false:
  {:key-points "..." :topics "..."}
  →
  {:key-points "..." :topics "..."}  ; No change
  ```

### Phase 3: Reporting Pipeline

#### Step 5: `generate-report-step`
```clojure
(flow/llm-step :generate-report model {...})
```
- **Type**: LLM step
- **Purpose**: Calls LLM to generate comprehensive report
- **Key concept**: LLM steps integrate with ChatModel protocol
- **State transformation**:
  ```clojure
  {:key-points "..." :topics "..." :methodology "..."}
  →
  {:key-points "..."
   :topics "..."
   :final-report "Comprehensive Analysis Report\n\n..."
   :report-generated-at 1234567890}
  ```

#### Step 6: `extract-report-sections-step`
```clojure
(flow/transform-step :extract-sections ...)
```
- **Type**: Transform step
- **Purpose**: Extracts structured sections from report
- **State transformation**:
  ```clojure
  {:final-report "Summary: ...\nConclusion: ..."}
  →
  {:final-report "..."
   :report-sections {:summary "..."
                     :conclusions "..."
                     :full-report "..."}}
  ```

#### Step 7: `calculate-quality-score-step`
```clojure
(flow/transform-step :calculate-quality-score ...)
```
- **Type**: Transform step
- **Purpose**: Calculates quality score based on multiple factors
- **State transformation**:
  ```clojure
  {:metadata {...} :deep-analysis-performed? true :final-report "..."}
  →
  {:metadata {...}
   :deep-analysis-performed? true
   :final-report "..."
   :quality-score 85
   :quality-grade "A"}
  ```

#### Step 8: `finalize-output-step`
```clojure
(flow/transform-step :finalize-output ...)
```
- **Type**: Transform step
- **Purpose**: Structures final output
- **State transformation**: Creates clean, structured output map

## Pipeline Composition

The pipeline is composed of three sub-pipelines:

```clojure
(def preprocessing-pipeline ...)
(def analysis-pipeline ...)
(def reporting-pipeline ...)

(def research-analysis-pipeline
  (flow/compose-pipelines
   [preprocessing-pipeline
    analysis-pipeline
    reporting-pipeline]))
```

**Key concept**: `compose-pipelines` chains multiple pipelines together, passing state through each one sequentially.

## Advanced: Branching Pipeline

The example also shows a **branching pipeline** that routes to different pipelines based on document type:

```clojure
(def adaptive-pipeline
  (flow/branch-pipeline
   document-type-router  ; Function that returns :research-paper, :technical-document, etc.
   {:research-paper research-paper-pipeline
    :technical-document technical-document-pipeline
    :business-document business-document-pipeline
    :default default-pipeline}))
```

**Key concept**: The router function examines state and returns a keyword, which selects which pipeline to execute.

## Key Concepts Demonstrated

### 1. **State Threading**
State flows through each step, accumulating information:
```
Initial → Step 1 → Step 2 → Step 3 → ... → Final
```

### 2. **Step Types**
- **Transform steps**: Pure functions, no LLM calls
- **LLM steps**: Integrate with ChatModel protocol
- **Parallel steps**: Concurrent execution
- **Conditional steps**: Conditional execution

### 3. **Composition**
- Steps compose into pipelines
- Pipelines compose into larger pipelines
- Everything is a function

### 4. **Observability**
```clojure
(pipeline-fn state
  {:on-step (fn [step-name state]
              (println "Step:" step-name))})
```

### 5. **Error Handling**
Steps can add `:error` and `:valid?` fields to state, which can be checked downstream.

## Usage Pattern

```clojure
;; 1. Define your pipeline
(def my-pipeline (flow/pipeline [step1 step2 step3]))

;; 2. Run it with initial state
(def result (my-pipeline {:input "..."} {}))

;; 3. Extract results
(:output result)
```

## Performance Benefits

1. **Parallel steps**: 4 analyses run concurrently instead of sequentially
2. **Conditional steps**: Skip expensive operations when not needed
3. **Composition**: Reuse sub-pipelines in different contexts

## Design Patterns

1. **Validation early**: Check inputs before expensive operations
2. **Parallelize independent work**: Use `parallel-step` for concurrent LLM calls
3. **Conditional processing**: Use `conditional-step` to optimize based on state
4. **Structured output**: Transform raw LLM responses into structured data
5. **Composition**: Build complex pipelines from simple, reusable parts

## Takeaways

1. **Flow is about data transformation**: Each step transforms state
2. **Everything is composable**: Steps → Pipelines → Larger pipelines
3. **State is explicit**: You can inspect state at any point
4. **Types of steps**: Transform, LLM, Parallel, Conditional
5. **Real-world patterns**: Validation, parallelization, conditional logic, composition

This example shows how `conduit.flow` enables building complex, maintainable LLM workflows using simple, composable functions.

