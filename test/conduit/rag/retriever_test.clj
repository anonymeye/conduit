(ns conduit.rag.retriever-test
  (:require [clojure.test :refer :all]
            [conduit.rag.retriever :as retriever]
            [conduit.rag.stores.memory :as mem]
            [conduit.rag.stores.core :as store]))

(deftest vector-retriever-test
  (testing "retrieves documents for query"
    (let [store (mem/memory-store)
          docs [{:id "1" :content "apple fruit"}
                {:id "2" :content "banana fruit"}
                {:id "3" :content "car vehicle"}]
          embeddings [[1.0 0.0] [0.9 0.1] [0.0 1.0]]
          embed-fn (fn [query]
                     (cond
                       (= query "fruit") [1.0 0.0]
                       (= query "vehicle") [0.0 1.0]
                       :else [0.5 0.5]))
          ret (retriever/vector-retriever store embed-fn)]
      (store/add-documents store docs embeddings)
      (let [results (retriever/retrieve ret "fruit" {:k 2})]
        (is (>= (count results) 1))
        (is (some #(= "apple" (subs (:content (:document %)) 0 5)) results)))))
  
  (testing "filters documents with filter-fn"
    (let [store (mem/memory-store)
          docs [{:id "1" :content "apple" :category "fruit"}
                {:id "2" :content "banana" :category "fruit"}
                {:id "3" :content "car" :category "vehicle"}]
          embeddings [[1.0 0.0] [0.9 0.1] [0.0 1.0]]
          embed-fn (constantly [1.0 0.0])
          ret (retriever/vector-retriever store embed-fn)]
      (store/add-documents store docs embeddings)
      (let [results (retriever/retrieve ret "query" {:k 10
                                                      :filter-fn #(= "fruit" (:category %))})]
        (is (every? #(= "fruit" (get-in % [:document :category])) results))))))

