(ns rag-example
  "Complete example of using Conduit's RAG system.
  
  This example demonstrates:
  1. Splitting documents into chunks
  2. Creating embeddings
  3. Storing in vector database
  4. Creating a retriever
  5. Building a RAG chain
  6. Querying documents"
  (:require [conduit.rag.splitters :as split]
            [conduit.rag.embeddings :as embed]
            [conduit.rag.stores.memory :as mem-store]
            [conduit.rag.stores.core :as store]
            [conduit.rag.retriever :as retriever]
            [conduit.rag.chain :as rag]
            [conduit.providers.mock :as mock]
            [conduit.core :as c]))

;; ============================================================================
;; Step 1: Create Mock Embedding Model
;; ============================================================================
;; In production, you'd use a real embedding model that implements Embeddable.
;; For this example, we create a simple mock.

(defrecord MockEmbeddingModel [dimension]
  c/Embeddable
  (embed [this texts]
    (c/embed this texts {}))
  (embed [this texts _opts]
    ;; Simple mock: create deterministic embeddings based on text hash
    (let [texts-vec (if (string? texts) [texts] texts)
          embeddings (mapv (fn [text]
                             ;; Create a simple deterministic embedding
                             (let [hash (hash text)
                                   base-vec (vec (repeatedly dimension
                                                              #(-> hash
                                                                   (mod 1000)
                                                                   (/ 1000.0)
                                                                   (- 0.5))))]
                               (vec (take dimension base-vec))))
                           texts-vec)]
      {:embeddings embeddings
       :usage {:input-tokens (reduce + (map count texts-vec))
               :output-tokens (* dimension (count texts-vec))}}))
  
  c/ChatModel
  (chat [this messages]
    (c/chat this messages {}))
  (chat [this messages _opts]
    {:role :assistant
     :content "This is a mock embedding model, not a chat model."
     :usage {:input-tokens 10 :output-tokens 5}
     :stop-reason :end-turn})
  
  (stream [_ _messages] (throw (ex-info "Not implemented" {})))
  (stream [_ _messages _opts] (throw (ex-info "Not implemented" {})))
  (model-info [_] {:provider :mock :model "mock-embedding"}))

(defn mock-embedding-model
  "Create a mock embedding model for examples.
  
  Arguments:
    dimension - Embedding dimension (default: 128)
  
  Returns:
    MockEmbeddingModel instance"
  ([] (mock-embedding-model 128))
  ([dimension]
   (->MockEmbeddingModel dimension)))

;; ============================================================================
;; Step 2: Prepare Your Documents
;; ============================================================================

(def sample-documents
  "Sample knowledge base about a fictional company."
  ["Conduit Inc. is a leading provider of AI infrastructure tools.
   Founded in 2023, the company specializes in building developer-friendly
   libraries for working with large language models.
   
   The company's flagship product is the Conduit library, which provides
   a unified interface for multiple LLM providers including OpenAI, Anthropic,
   and xAI. Conduit supports features like streaming, tool calling, and
   structured output generation.
   
   Conduit is built in Clojure and emphasizes composability, allowing
   developers to build complex AI workflows using simple, functional patterns.
   The library includes interceptors for retry logic, rate limiting, caching,
   and cost tracking."
   
   "The Conduit library architecture is based on protocols, making it easy
   to extend and customize. Core protocols include ChatModel for text
   generation, Embeddable for embeddings, and Wrappable for handler
   composition.
   
   Key features of Conduit:
   - Multi-provider support with unified API
   - Streaming responses with core.async
   - Tool calling and agent loops
   - Structured output generation
   - RAG (Retrieval-Augmented Generation) support
   - Comprehensive error handling
   - Built-in interceptor system for cross-cutting concerns"
   
   "Conduit's RAG system allows you to ground LLM responses in your own
   documents. The system includes text splitters for chunking documents,
   embedding generation, vector stores for similarity search, and a complete
   RAG chain that combines retrieval with generation.
   
   To use RAG with Conduit:
   1. Split your documents into chunks
   2. Generate embeddings for each chunk
   3. Store in a vector database
   4. Create a retriever
   5. Build a RAG chain with your chat model
   6. Query your documents"])

;; ============================================================================
;; Step 3: Complete RAG Workflow Example
;; ============================================================================

(defn complete-rag-example
  "Complete example showing the full RAG workflow."
  []
  (println "=== Conduit RAG Example ===\n")
  
  ;; Step 1: Split documents into chunks
  (println "Step 1: Splitting documents...")
  (let [splitter (split/recursive-splitter {:chunk-size 200
                                            :chunk-overlap 50})
        document-chunks (mapcat (fn [doc]
                                   (split/split splitter doc))
                                 sample-documents)]
    (println (str "  Created " (count document-chunks) " chunks\n"))
    
    ;; Step 2: Create document objects with metadata
    (println "Step 2: Creating document objects...")
    (let [documents (map-indexed (fn [i chunk]
                                   {:id (str "doc-" i)
                                    :content chunk
                                    :metadata {:source "company-knowledge-base"
                                              :chunk-index i
                                              :total-chunks (count document-chunks)}})
                                 document-chunks)]
      (println (str "  Created " (count documents) " documents\n"))
      
      ;; Step 3: Create embedding model
      (println "Step 3: Creating embedding model...")
      (let [embedding-model (mock-embedding-model 128)
            embedding-wrapper (embed/provider-embedding-model embedding-model)]
        (println "  Embedding model ready\n")
        
        ;; Step 4: Generate embeddings
        (println "Step 4: Generating embeddings...")
        (let [embeddings (embed/embed-documents embedding-wrapper documents {:batch-size 10})]
          (println (str "  Generated " (count embeddings) " embeddings\n"))
          
          ;; Step 5: Store in vector database
          (println "Step 5: Storing in vector database...")
          (let [vector-store (mem-store/memory-store)]
            (store/add-documents vector-store documents embeddings)
            (println (str "  Stored " (store/count-documents vector-store) " documents\n"))
            
            ;; Step 6: Create retriever
            (println "Step 6: Creating retriever...")
            (let [my-retriever (retriever/vector-retriever
                                 vector-store
                                 (fn [query]
                                   (embed/embed-query embedding-wrapper query)))]
              (println "  Retriever ready\n")
              
              ;; Step 7: Create chat model
              (println "Step 7: Creating chat model...")
              (let [chat-model (mock/model {:response-fn
                                            (fn [messages]
                                              (let [user-msg (last messages)
                                                    content (:content user-msg)]
                                                {:role :assistant
                                                 :content (str "Based on the provided context, I can answer: "
                                                              (if (re-find #"(?i)what|tell|explain" content)
                                                                "Conduit is a Clojure library for AI workflows. "
                                                                "Here's what I found: ")
                                                              "The context mentions various features of Conduit including "
                                                              "multi-provider support, streaming, tool calling, and RAG capabilities.")
                                                 :usage {:input-tokens 50 :output-tokens 30}
                                                 :stop-reason :end-turn}))})]
                (println "  Chat model ready\n")
                
                ;; Step 8: Create RAG chain
                (println "Step 8: Creating RAG chain...")
                (let [qa-chain (rag/rag-chain {:model chat-model
                                               :retriever my-retriever
                                               :k 3
                                               :template "Use the following context to answer the question.
If you cannot answer based on the context, say so.

Context:
{context}

Question: {question}

Answer:"})]
                  (println "  RAG chain ready\n")
                  
                  ;; Step 9: Query the knowledge base
                  (println "Step 9: Querying knowledge base...\n")
                  (doseq [question ["What is Conduit?"
                                   "What features does Conduit support?"
                                   "How do I use RAG with Conduit?"]]
                    (println (str "Q: " question))
                    (let [result (qa-chain question)]
                      (println (str "A: " (:answer result)))
                      (println (str "   Sources: " (count (:sources result)) " documents"))
                      (println (str "   Usage: " (:usage result)))
                      (println)))
                  
                  (println "\n=== Example Complete ==="))))))))))

;; ============================================================================
;; Simplified Usage Example
;; ============================================================================

(defn simple-rag-example
  "Simplified example with inline setup."
  []
  (let [;; 1. Documents
        docs [{:id "1" :content "Conduit is a Clojure AI library."}
              {:id "2" :content "It supports multiple LLM providers."}
              {:id "3" :content "RAG allows grounding responses in documents."}]
        
        ;; 2. Embedding model
        emb-model (embed/provider-embedding-model (mock-embedding-model 64))
        embeddings (embed/embed-documents emb-model docs {})
        
        ;; 3. Vector store
        store (mem-store/memory-store)]
    (store/add-documents store docs embeddings)
    
    ;; 4. Retriever
    (let [ret (retriever/vector-retriever
                store
                #(embed/embed-query emb-model %))
          
          ;; 5. Chat model
          chat-model (mock/model {:response-fn
                                  (fn [_]
                                    {:role :assistant
                                     :content "Based on the context provided..."
                                     :usage {:input-tokens 10 :output-tokens 5}
                                     :stop-reason :end-turn})})
          
          ;; 6. RAG chain
          qa (rag/rag-chain {:model chat-model
                            :retriever ret
                            :k 2})]
      
      ;; 7. Query
      (qa "What is Conduit?"))))

;; ============================================================================
;; Run Examples
;; ============================================================================

(comment
  ;; Run the complete example
  (complete-rag-example)
  
  ;; Run the simple example
  (simple-rag-example)
  
  ;; Test individual components
  (let [splitter (split/character-splitter {:chunk-size 50})]
    (split/split splitter "This is a test document that will be split."))
  
  (let [emb-model (embed/provider-embedding-model (mock-embedding-model 32))
        result (embed/embed-query emb-model "test query")]
    (println "Embedding dimension:" (count result)))
  )

