(ns conduit.rag.stores.core
  "Core protocol for vector stores.")

(defprotocol VectorStore
  "Protocol for vector stores that support similarity search."
  
  (add-documents
    [store docs embeddings]
    "Add documents with their embeddings.
    
    Arguments:
      store      - VectorStore instance
      docs       - Vector of document maps
      embeddings - Vector of embedding vectors (one per document)
    
    Returns:
      Store instance (for chaining)")
  
  (similarity-search
    [store query-embedding k]
    "Find k most similar documents.
    
    Arguments:
      store          - VectorStore instance
      query-embedding - Query embedding vector
      k              - Number of results to return
    
    Returns:
      Vector of document maps")
  
  (similarity-search-with-score
    [store query-embedding k]
    "Find k most similar documents with similarity scores.
    
    Arguments:
      store          - VectorStore instance
      query-embedding - Query embedding vector
      k              - Number of results to return
    
    Returns:
      Vector of maps with :document, :score, and :rank keys")
  
  (delete
    [store ids]
    "Delete documents by ID.
    
    Arguments:
      store - VectorStore instance
      ids   - Vector of document IDs to delete
    
    Returns:
      Store instance (for chaining)")
  
  (count-documents
    [store]
    "Count total documents in the store.
    
    Arguments:
      store - VectorStore instance
    
    Returns:
      Integer count of documents"))

