# RAG Usage Guide

A practical guide to using Conduit's RAG (Retrieval-Augmented Generation) system.

## Quick Start

Here's a minimal example to get you started:

```clojure
(require '[conduit.rag.splitters :as split]
         '[conduit.rag.embeddings :as embed]
         '[conduit.rag.stores.memory :as mem-store]
         '[conduit.rag.stores.core :as store]
         '[conduit.rag.retriever :as retriever]
         '[conduit.rag.chain :as rag])

;; 1. Your documents
(def documents
  [{:id "1" :content "Conduit is a Clojure library for AI."}
   {:id "2" :content "It supports multiple LLM providers."}
   {:id "3" :content "RAG grounds responses in your data."}])

;; 2. Create embedding model (you'll use a real one in production)
(def embedding-model (your-embedding-model))  ; Must implement Embeddable
(def emb-wrapper (embed/provider-embedding-model embedding-model))

;; 3. Generate embeddings
(def embeddings (embed/embed-documents emb-wrapper documents {}))

;; 4. Store in vector database
(def store (mem-store/memory-store))
(store/add-documents store documents embeddings)

;; 5. Create retriever
(def retriever
  (retriever/vector-retriever
    store
    #(embed/embed-query emb-wrapper %)))

;; 6. Create chat model (your LLM)
(def chat-model (your-chat-model))  ; Must implement ChatModel

;; 7. Build RAG chain
(def qa (rag/rag-chain {:model chat-model
                        :retriever retriever
                        :k 3}))

;; 8. Query!
(qa "What is Conduit?")
;; => {:answer "..." :sources [...] :usage {...}}
```

## Step-by-Step Breakdown

### 1. Split Large Documents

If you have large documents, split them first:

```clojure
(def splitter (split/recursive-splitter {:chunk-size 500
                                         :chunk-overlap 100}))

(def chunks (split/split splitter large-document-text))

(def documents
  (map-indexed (fn [i chunk]
                 {:id (str "chunk-" i)
                  :content chunk
                  :metadata {:source "my-doc.txt"
                            :chunk-index i}})
               chunks))
```

**Available splitters:**
- `character-splitter` - Simple character-based splitting
- `recursive-splitter` - Tries multiple separators (recommended)
- `sentence-splitter` - Splits by sentences
- `markdown-splitter` - Splits markdown by headers

### 2. Create Embeddings

You need a model that implements the `Embeddable` protocol:

```clojure
;; Wrap your embedding model
(def emb-model (embed/provider-embedding-model your-embedding-model))

;; Embed all documents (with batching)
(def embeddings
  (embed/embed-documents emb-model documents {:batch-size 100}))

;; Embed a query
(def query-embedding (embed/embed-query emb-model "your question"))
```

### 3. Vector Store

Store documents with their embeddings:

```clojure
(def store (mem-store/memory-store))

;; Add documents
(store/add-documents store documents embeddings)

;; Search (returns documents)
(store/similarity-search store query-embedding 5)

;; Search with scores
(store/similarity-search-with-score store query-embedding 5)

;; Delete documents
(store/delete store ["doc-1" "doc-2"])

;; Count documents
(store/count-documents store)
```

### 4. Retriever

Create a retriever that combines embedding and search:

```clojure
(def retriever
  (retriever/vector-retriever
    store
    (fn [query]
      (embed/embed-query emb-model query))))

;; Retrieve documents
(retriever/retrieve retriever "your question" {:k 5})

;; With filtering
(retriever/retrieve retriever "your question"
                   {:k 5
                    :filter-fn (fn [doc]
                                 (= "category-a" (get-in doc [:metadata :category])))})
```

### 5. RAG Chain

Combine retrieval with LLM generation:

```clojure
(def qa
  (rag/rag-chain {:model chat-model
                  :retriever retriever
                  :k 3  ; Number of documents to retrieve
                  :template "Answer based on: {context}\n\nQuestion: {question}"}))

;; Query
(def result (qa "What is Conduit?"))
;; => {:answer "..." 
;;     :sources [doc1 doc2 doc3]
;;     :usage {:input-tokens ... :output-tokens ...}
;;     :scores [0.95 0.87 0.82]}
```

**Custom context formatting:**

```clojure
(def qa
  (rag/rag-chain {:model chat-model
                  :retriever retriever
                  :format-context (fn [results]
                                    (->> results
                                         (map-indexed (fn [i r]
                                                         (str (inc i) ". "
                                                              (:content (:document r)))))
                                         (str/join "\n\n")))}))
```

## Complete Example

See `examples/rag_example.clj` for a complete working example with:
- Document splitting
- Embedding generation
- Vector storage
- Retrieval
- RAG chain setup
- Multiple queries

## Production Considerations

1. **Embedding Model**: Use a real embedding model (OpenAI, Cohere, etc.) that implements `Embeddable`
2. **Vector Store**: Consider persistent stores (pgvector, Pinecone, Weaviate) for production
3. **Chunking**: Tune `chunk-size` and `chunk-overlap` for your use case
4. **Filtering**: Use metadata filtering to scope searches
5. **Batch Size**: Adjust embedding batch size based on API limits
6. **Error Handling**: Wrap operations in try-catch for production

## Tips

- **Chunk Size**: 500-1000 characters works well for most cases
- **Overlap**: 10-20% of chunk size helps maintain context
- **K Value**: Start with 3-5 documents, adjust based on results
- **Metadata**: Add rich metadata to documents for better filtering
- **Templates**: Customize the prompt template for your domain

