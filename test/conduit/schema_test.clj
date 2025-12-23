(ns conduit.schema-test
  (:require [clojure.test :refer :all]
            [conduit.schema :as schema]))

(deftest test-validation
  (testing "valid? with valid data"
    (is (true? (schema/valid? schema/Role :user)))
    (is (true? (schema/valid? schema/Role :assistant)))
    (is (false? (schema/valid? schema/Role :invalid))))
  
  (testing "validate with valid message"
    (let [msg {:role :user :content "Hello"}]
      (is (= msg (schema/validate schema/Message msg)))))
  
  (testing "validate with invalid message throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate schema/Message {:role :invalid}))))
  
  (testing "explain returns errors"
    (let [errors (schema/explain schema/Role :invalid)]
      (is (some? errors)))))

(deftest test-message-schemas
  (testing "simple user message"
    (is (true? (schema/valid? schema/Message
                              {:role :user :content "Hello"}))))
  
  (testing "message with content blocks"
    (is (true? (schema/valid? schema/Message
                              {:role :user 
                               :content [{:type :text :text "Hello"}]}))))
  
  (testing "tool message"
    (is (true? (schema/valid? schema/Message
                              {:role :tool
                               :tool-call-id "call_123"
                               :content "result"})))))

(deftest test-response-schema
  (testing "valid response"
    (is (true? (schema/valid? schema/Response
                              {:role :assistant
                               :content "Hello"
                               :model "grok-3"
                               :stop-reason :end-turn
                               :usage {:input-tokens 10
                                      :output-tokens 5}})))))

(deftest test-tool-schema
  (testing "valid tool"
    (is (true? (schema/valid? schema/Tool
                              {:name "get_weather"
                               :description "Get weather"
                               :schema [:map [:location :string]]})))))

(deftest test-malli-json-schema
  (testing "convert simple schema"
    (let [json-schema (schema/malli->json-schema 
                       [:map [:name :string] [:age :int]])]
      (is (= "object" (:type json-schema)))
      (is (contains? (:properties json-schema) :name))
      (is (contains? (:properties json-schema) :age)))))

(deftest test-schema-registry
  (testing "get-schema"
    (is (some? (schema/get-schema :message)))
    (is (some? (schema/get-schema :response)))
    (is (nil? (schema/get-schema :nonexistent)))))

