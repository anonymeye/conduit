(ns conduit.rag.retriever
  "Document retrieval system for RAG."
  (:require [conduit.rag.stores.core :as store]))

(defprotocol Retriever
  "Protocol for document retrievers."
  (retrieve
    [this query opts]
    "Retrieve relevant documents for a query.
    
    Arguments:
      this  - Retriever instance
      query - Query string
      opts  - Options map:
              :k         - Number of documents to retrieve (default: 5)
              :filter-fn - Optional function to filter documents
                           (fn [document] boolean)
    
    Returns:
      Vector of RetrievalResult maps with :document, :score, :rank"))

(defn vector-retriever
  "Create retriever backed by vector store.
  
  Arguments:
    store    - VectorStore instance
    embed-fn - Function to embed query string
               (fn [query] embedding-vector)
    
  Returns:
    Retriever instance"
  [store embed-fn]
  (when-not (satisfies? store/VectorStore store)
    (throw (ex-info "Store must implement VectorStore protocol"
                    {:type :validation-error
                     :store store})))
  (reify Retriever
    (retrieve [_ query {:keys [k filter-fn]
                        :or {k 5}}]
      (let [query-embedding (embed-fn query)
            results (store/similarity-search-with-score store query-embedding k)]
        (if filter-fn
          (->> results
               (filter #(filter-fn (:document %)))
               vec)
          (vec results))))))

