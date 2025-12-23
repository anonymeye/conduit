(ns conduit.core-test
  (:require [clojure.test :refer :all]
            [conduit.core :as c]
            [conduit.schema :as schema]))

(deftest test-message-builders
  (testing "system-message"
    (let [msg (c/system-message "You are helpful")]
      (is (= :system (:role msg)))
      (is (= "You are helpful" (:content msg)))))
  
  (testing "user-message"
    (let [msg (c/user-message "Hello")]
      (is (= :user (:role msg)))
      (is (= "Hello" (:content msg)))))
  
  (testing "assistant-message"
    (let [msg (c/assistant-message "Hi there")]
      (is (= :assistant (:role msg)))
      (is (= "Hi there" (:content msg)))))
  
  (testing "tool-message"
    (let [msg (c/tool-message "call_123" "result")]
      (is (= :tool (:role msg)))
      (is (= "call_123" (:tool-call-id msg)))
      (is (= "result" (:content msg))))))

(deftest test-content-builders
  (testing "text-block"
    (let [block (c/text-block "Hello")]
      (is (= :text (:type block)))
      (is (= "Hello" (:text block)))))
  
  (testing "image-url"
    (let [source (c/image-url "https://example.com/img.jpg")]
      (is (= :url (:type source)))
      (is (= "https://example.com/img.jpg" (:url source)))))
  
  (testing "image-base64"
    (let [source (c/image-base64 "base64data" "image/png")]
      (is (= :base64 (:type source)))
      (is (= "base64data" (:data source)))
      (is (= "image/png" (:media-type source))))))

(deftest test-validation
  (testing "validate-messages with valid messages"
    (let [messages [(c/user-message "Hello")]]
      (is (= messages (c/validate-messages messages)))))
  
  (testing "validate-options with empty opts"
    (is (= {} (c/validate-options {})))))

(deftest test-response-helpers
  (testing "extract-content from string"
    (let [response {:content "Hello"}]
      (is (= "Hello" (c/extract-content response)))))
  
  (testing "extract-content from vector"
    (let [response {:content [{:type :text :text "Hello"}]}]
      (is (= "Hello" (c/extract-content response)))))
  
  (testing "extract-tool-calls with no tools"
    (let [response {}]
      (is (= [] (c/extract-tool-calls response)))))
  
  (testing "has-tool-calls?"
    (is (false? (c/has-tool-calls? {})))
    (is (true? (c/has-tool-calls? {:tool-calls [{:id "1"}]}))))
  
  (testing "token helpers"
    (let [response {:usage {:input-tokens 10 :output-tokens 20}}]
      (is (= 10 (c/input-tokens response)))
      (is (= 20 (c/output-tokens response)))
      (is (= 30 (c/total-tokens response))))))

