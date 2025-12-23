(ns conduit.rag.stores.memory-test
  (:require [clojure.test :refer :all]
            [conduit.rag.stores.memory :as mem]
            [conduit.rag.stores.core :as core]))

(deftest cosine-similarity-test
  (testing "calculates cosine similarity"
    (let [a [1.0 0.0]
          b [1.0 0.0]]
      (is (= 1.0 (mem/cosine-similarity a b))))
    
    (let [a [1.0 0.0]
          b [0.0 1.0]]
      (is (= 0.0 (mem/cosine-similarity a b))))))

(deftest memory-store-test
  (testing "adds documents with embeddings"
    (let [store (mem/memory-store)
          docs [{:id "1" :content "Hello"}
                {:id "2" :content "World"}]
          embeddings [[1.0 0.0] [0.0 1.0]]]
      (core/add-documents store docs embeddings)
      (is (= 2 (core/count-documents store)))))
  
  (testing "searches by similarity"
    (let [store (mem/memory-store)
          docs [{:id "1" :content "apple"}
                {:id "2" :content "banana"}
                {:id "3" :content "orange"}]
          embeddings [[1.0 0.0 0.0]
                      [0.0 1.0 0.0]
                      [0.0 0.0 1.0]]]
      (core/add-documents store docs embeddings)
      (let [results (core/similarity-search-with-score store [1.0 0.0 0.0] 2)]
        (is (= 2 (count results)))
        (is (= "apple" (:content (:document (first results))))))))
  
  (testing "deletes documents by id"
    (let [store (mem/memory-store)
          docs [{:id "1" :content "Hello"}
                {:id "2" :content "World"}]
          embeddings [[1.0 0.0] [0.0 1.0]]]
      (core/add-documents store docs embeddings)
      (core/delete store ["1"])
      (is (= 1 (core/count-documents store)))
      (let [results (core/similarity-search store [1.0 0.0] 10)]
        (is (not-any? #(= "1" (:id %)) results))))))

