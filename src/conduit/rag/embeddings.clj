(ns conduit.rag.embeddings
  "Embedding utilities for RAG document processing."
  (:require [conduit.core :as c]))

(defprotocol EmbeddingModel
  "Protocol for embedding models."
  (embed [this texts]
    "Generate embeddings for texts.
    
    Arguments:
      this  - The embedding model instance
      texts - String or vector of strings
      
    Returns:
      Map with :embeddings (vector of float vectors) and :usage"))

(defn provider-embedding-model
  "Create embedding model from a provider that implements Embeddable protocol.
  
  Arguments:
    model - Model instance implementing Embeddable protocol
    
  Returns:
    EmbeddingModel instance"
  [model]
  (when-not (satisfies? c/Embeddable model)
    (throw (ex-info "Model must implement Embeddable protocol"
                    {:type :validation-error
                     :model model})))
  (reify EmbeddingModel
    (embed [_ texts]
      (let [result (c/embed model texts)
            ;; Extract model name from config (embedding models store info in config)
            model-name (when (instance? clojure.lang.IRecord model)
                        (or (get-in model [:config :model])
                            (get-in model [:config :base-url])
                            (str (class model))))]
        {:embeddings (:embeddings result)
         :usage (:usage result)
         :model model-name}))))

(defn embed-documents
  "Embed multiple documents with batching.
  
  Arguments:
    model     - EmbeddingModel instance
    documents - Vector of documents (maps with :content key)
    opts      - Options map:
                :batch-size - Batch size for embedding (default: 100)
                :on-batch   - Optional callback function (batch-index, total-batches)
  
  Returns:
    Vector of embedding vectors (one per document)"
  [model documents {:keys [batch-size on-batch]
                    :or {batch-size 100}}]
  (let [batches (partition-all batch-size documents)
        total-batches (count batches)]
    (mapcat (fn [[batch-idx batch]]
              (when on-batch
                (on-batch (inc batch-idx) total-batches))
              (let [texts (mapv :content batch)
                    result (embed model texts)]
                (:embeddings result)))
            (map-indexed vector batches))))

(defn embed-query
  "Embed a single query string.
  
  Arguments:
    model - EmbeddingModel instance
    query - Query string
    
  Returns:
    Single embedding vector"
  [model query]
  (first (:embeddings (embed model query))))

