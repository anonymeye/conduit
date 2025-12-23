(ns conduit.rag.splitters-test
  (:require [clojure.test :refer :all]
            [conduit.rag.splitters :as split]))

(deftest character-splitter-test
  (testing "splits text by character count"
    (let [splitter (split/character-splitter {:chunk-size 10 :chunk-overlap 0})]
      (is (= ["Hello" "World"] (split/split splitter "Hello\n\nWorld")))))
  
  (testing "handles text smaller than chunk size"
    (let [splitter (split/character-splitter {:chunk-size 100})]
      (is (= ["Short text"] (split/split splitter "Short text")))))
  
  (testing "adds overlap between chunks"
    (let [splitter (split/character-splitter {:chunk-size 5 :chunk-overlap 2})]
      (let [chunks (split/split splitter "123456789")]
        (is (> (count chunks) 1))
        (is (some #(> (count %) 5) chunks))))))

(deftest recursive-splitter-test
  (testing "splits by multiple separators"
    (let [splitter (split/recursive-splitter {:chunk-size 20 :chunk-overlap 0})]
      (let [chunks (split/split splitter "First paragraph.\n\nSecond paragraph.\nThird line.")]
        (is (>= (count chunks) 2)))))
  
  (testing "handles text with no separators"
    (let [splitter (split/recursive-splitter {:chunk-size 10})]
      (let [chunks (split/split splitter "onetwothree")]
        (is (>= (count chunks) 1))))))

(deftest sentence-splitter-test
  (testing "splits by sentences"
    (let [splitter (split/sentence-splitter {:chunk-size 50 :chunk-overlap 0})]
      (let [chunks (split/split splitter "First sentence. Second sentence. Third sentence.")]
        (is (>= (count chunks) 1))))))

(deftest markdown-splitter-test
  (testing "splits markdown by headers"
    (let [splitter (split/markdown-splitter {:chunk-size 50 :chunk-overlap 0})]
      (let [chunks (split/split splitter "# Header 1\nContent here.\n## Header 2\nMore content.")]
        (is (>= (count chunks) 2))))))

