(ns conduit.rag.stores.memory
  "In-memory vector store implementation."
  (:require [conduit.rag.stores.core :as core]))

(defn cosine-similarity
  "Calculate cosine similarity between two vectors.
  
  Arguments:
    a - First vector
    b - Second vector
    
  Returns:
    Cosine similarity score (0.0 to 1.0)"
  [a b]
  (let [dot (reduce + (map * a b))
        mag-a (Math/sqrt (reduce + (map #(* % %) a)))
        mag-b (Math/sqrt (reduce + (map #(* % %) b)))]
    (if (or (zero? mag-a) (zero? mag-b))
      0.0
      (/ dot (* mag-a mag-b)))))

(defn memory-store
  "Create an in-memory vector store.
  
  Returns:
    VectorStore instance"
  []
  (let [data (atom {:documents []
                    :embeddings []})]
    (reify core/VectorStore
      (add-documents [this docs embeddings]
        (when (not= (count docs) (count embeddings))
          (throw (ex-info "Number of documents must match number of embeddings"
                          {:type :validation-error
                           :doc-count (count docs)
                           :embedding-count (count embeddings)})))
        (swap! data (fn [d]
                      (-> d
                          (update :documents into docs)
                          (update :embeddings into embeddings))))
        this)
      
      (similarity-search [this query-embedding k]
        (map :document (core/similarity-search-with-score this query-embedding k)))
      
      (similarity-search-with-score [_ query-embedding k]
        (let [{:keys [documents embeddings]} @data
              scored (->> (map-indexed
                           (fn [i doc]
                             {:document doc
                              :score (cosine-similarity query-embedding (nth embeddings i))
                              :rank i})
                           documents)
                          (sort-by :score >)
                          (take k)
                          (map-indexed (fn [rank item]
                                         (assoc item :rank rank)))
                          vec)]
          scored))
      
      (delete [this ids]
        (let [id-set (set ids)]
          (swap! data (fn [{:keys [documents embeddings]}]
                        (let [keep-indices (->> documents
                                                 (map-indexed vector)
                                                 (remove (fn [[_ doc]]
                                                           (id-set (:id doc))))
                                                 (map first))]
                          {:documents (mapv #(nth documents %) keep-indices)
                           :embeddings (mapv #(nth embeddings %) keep-indices)})))
        this))
      
      (count-documents [_]
        (count (:documents @data))))))

