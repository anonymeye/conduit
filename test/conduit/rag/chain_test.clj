(ns conduit.rag.chain-test
  (:require [clojure.test :refer :all]
            [conduit.rag.chain :as rag]
            [conduit.rag.retriever :as retriever]
            [conduit.rag.stores.memory :as mem]
            [conduit.rag.stores.core :as store]
            [conduit.providers.mock :as mock]))

(deftest rag-chain-test
  (testing "creates RAG chain function"
    (let [model (mock/model {:response-fn (fn [_] {:content "Based on the context, the answer is X."})})
          store (mem/memory-store)
          docs [{:id "1" :content "Context about X"}
                {:id "2" :content "More context"}]
          embeddings [[1.0 0.0] [0.9 0.1]]
          embed-fn (constantly [1.0 0.0])
          ret (retriever/vector-retriever store embed-fn)
          qa (rag/rag-chain {:model model :retriever ret :k 2})]
      (store/add-documents store docs embeddings)
      (let [result (qa "What is X?")]
        (is (contains? result :answer))
        (is (contains? result :sources))
        (is (contains? result :usage))
        (is (string? (:answer result)))
        (is (vector? (:sources result))))))
  
  (testing "uses custom template"
    (let [model (mock/model {:response-fn (fn [msgs] {:content "Custom answer"})})
          store (mem/memory-store)
          embed-fn (constantly [1.0 0.0])
          ret (retriever/vector-retriever store embed-fn)
          template "Custom template with {context} and {question}"
          qa (rag/rag-chain {:model model
                            :retriever ret
                            :template template})]
      (let [result (qa "test")]
        (is (string? (:answer result)))))))

