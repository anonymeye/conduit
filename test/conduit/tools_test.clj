(ns conduit.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [conduit.tools :as tools]))

;; -----------------------------------------------------------------------------
;; Test Tools

(def weather-tool
  (tools/tool "get_weather"
              "Get the current weather for a location"
              [:map
               [:location :string]
               [:unit {:optional true} [:enum "celsius" "fahrenheit"]]]
              (fn [{:keys [location unit]}]
                {:temperature 22
                 :unit (or unit "celsius")
                 :location location
                 :conditions "sunny"})))

(def calculator-tool
  (tools/tool "calculate"
              "Perform a calculation"
              [:map
               [:operation [:enum "add" "subtract" "multiply" "divide"]]
               [:a :int]
               [:b :int]]
              (fn [{:keys [operation a b]}]
                (case operation
                  "add" (+ a b)
                  "subtract" (- a b)
                  "multiply" (* a b)
                  "divide" (/ a b)))))

(def error-tool
  (tools/tool "error_tool"
              "A tool that throws an error"
              [:map [:input :string]]
              (fn [_]
                (throw (ex-info "Tool error" {:type :test-error})))))

;; -----------------------------------------------------------------------------
;; Tool Creation Tests

(deftest tool-creation-test
  (testing "Create tool with all fields"
    (let [tool (tools/tool "test" "Test tool" [:map [:x :int]] identity)]
      (is (= "test" (:name tool)))
      (is (= "Test tool" (:description tool)))
      (is (some? (:schema tool)))
      (is (fn? (:fn tool)))))
  
  (testing "Tool preconditions"
    (is (thrown? AssertionError
                 (tools/tool nil "desc" [:map] identity)))
    (is (thrown? AssertionError
                 (tools/tool "name" nil [:map] identity)))
    (is (thrown? AssertionError
                 (tools/tool "name" "desc" nil identity)))
    (is (thrown? AssertionError
                 (tools/tool "name" "desc" [:map] nil)))))

;; -----------------------------------------------------------------------------
;; Validation Tests

(deftest validate-tool-args-test
  (testing "Valid arguments"
    (let [args {:location "Tokyo" :unit "celsius"}
          result (tools/validate-tool-args weather-tool args)]
      (is (= args result))))
  
  (testing "Valid arguments with optional field missing"
    (let [args {:location "Tokyo"}
          result (tools/validate-tool-args weather-tool args)]
      (is (= args result))))
  
  (testing "Invalid arguments - missing required field"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tools/validate-tool-args weather-tool {}))))
  
  (testing "Invalid arguments - wrong type"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tools/validate-tool-args weather-tool {:location 123}))))
  
  (testing "Invalid enum value"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tools/validate-tool-args weather-tool 
                                           {:location "Tokyo" :unit "kelvin"})))))

;; -----------------------------------------------------------------------------
;; Execution Tests

(deftest execute-tool-test
  (testing "Execute tool successfully"
    (let [result (tools/execute-tool weather-tool {:location "Tokyo"})]
      (is (map? result))
      (is (= "Tokyo" (:location result)))
      (is (= 22 (:temperature result)))
      (is (= "celsius" (:unit result)))))
  
  (testing "Execute tool with optional parameter"
    (let [result (tools/execute-tool weather-tool 
                                     {:location "Paris" :unit "fahrenheit"})]
      (is (= "fahrenheit" (:unit result)))))
  
  (testing "Execute calculator tool"
    (is (= 5 (tools/execute-tool calculator-tool 
                                 {:operation "add" :a 2 :b 3})))
    (is (= 6 (tools/execute-tool calculator-tool 
                                 {:operation "multiply" :a 2 :b 3}))))
  
  (testing "Execute tool with validation error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Tool execution failed"
                          (tools/execute-tool weather-tool {}))))
  
  (testing "Execute tool with execution error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Tool execution failed"
                          (tools/execute-tool error-tool {:input "test"}))))
  
  (testing "Execution error includes context"
    (try
      (tools/execute-tool error-tool {:input "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :tool-error (:type data)))
          (is (= "error_tool" (:tool data)))
          (is (= {:input "test"} (:args data))))))))

;; -----------------------------------------------------------------------------
;; Tool Lookup Tests

(deftest find-tool-test
  (testing "Find existing tool"
    (let [tools [weather-tool calculator-tool]
          found (tools/find-tool tools "get_weather")]
      (is (some? found))
      (is (= "get_weather" (:name found)))))
  
  (testing "Find non-existing tool"
    (let [tools [weather-tool calculator-tool]
          found (tools/find-tool tools "nonexistent")]
      (is (nil? found))))
  
  (testing "Find in empty collection"
    (is (nil? (tools/find-tool [] "test")))))

;; -----------------------------------------------------------------------------
;; Tool Call Execution Tests

(deftest execute-tool-calls-test
  (testing "Execute single tool call"
    (let [tool-calls [{:id "call_1"
                       :function {:name "get_weather"
                                  :arguments {:location "Tokyo"}}}]
          results (tools/execute-tool-calls [weather-tool] tool-calls)]
      (is (= 1 (count results)))
      (let [result (first results)]
        (is (= :tool (:role result)))
        (is (= "call_1" (:tool-call-id result)))
        (is (string? (:content result))))))
  
  (testing "Execute multiple tool calls"
    (let [tool-calls [{:id "call_1"
                       :function {:name "get_weather"
                                  :arguments {:location "Tokyo"}}}
                      {:id "call_2"
                       :function {:name "calculate"
                                  :arguments {:operation "add" :a 2 :b 3}}}]
          results (tools/execute-tool-calls [weather-tool calculator-tool] tool-calls)]
      (is (= 2 (count results)))
      (is (every? #(= :tool (:role %)) results))
      (is (= "call_1" (:tool-call-id (first results))))
      (is (= "call_2" (:tool-call-id (second results))))))
  
  (testing "Execute tool call with unknown tool"
    (let [tool-calls [{:id "call_1"
                       :function {:name "unknown_tool"
                                  :arguments {}}}]
          results (tools/execute-tool-calls [weather-tool] tool-calls)]
      (is (= 1 (count results)))
      (let [result (first results)]
        (is (= :tool (:role result)))
        (is (true? (:is-error result)))
        (is (string? (:content result))))))
  
  (testing "Execute tool call with execution error"
    (let [tool-calls [{:id "call_1"
                       :function {:name "error_tool"
                                  :arguments {:input "test"}}}]
          results (tools/execute-tool-calls [error-tool] tool-calls)]
      (is (= 1 (count results)))
      (let [result (first results)]
        (is (= :tool (:role result)))
        (is (true? (:is-error result)))))))

;; -----------------------------------------------------------------------------
;; Result Formatting Tests

(deftest format-tool-result-test
  (testing "Format string result"
    (is (= "hello" (tools/format-tool-result "hello"))))
  
  (testing "Format map result"
    (let [result (tools/format-tool-result {:temp 22 :location "Tokyo"})]
      (is (string? result))
      (is (re-find #"temp" result))
      (is (re-find #"Tokyo" result))))
  
  (testing "Format number result"
    (let [result (tools/format-tool-result 42)]
      (is (string? result))
      (is (= "42" result))))
  
  (testing "Format vector result"
    (let [result (tools/format-tool-result [1 2 3])]
      (is (string? result))
      (is (re-find #"\[" result)))))

;; -----------------------------------------------------------------------------
;; Schema Conversion Tests

(deftest schema-conversion-test
  (testing "Convert Malli schema to JSON Schema"
    (let [malli-schema [:map
                        [:name :string]
                        [:age :int]
                        [:email {:optional true} :string]]
          json-schema (tools/malli->json-schema malli-schema)]
      (is (map? json-schema))
      (is (= "object" (:type json-schema)))
      (is (map? (:properties json-schema)))
      (is (contains? (:properties json-schema) :name))
      (is (contains? (:properties json-schema) :age))))
  
  (testing "Convert tool to JSON Schema format"
    (let [json-tool (tools/tool->json-schema weather-tool)]
      (is (= "function" (:type json-tool)))
      (is (map? (:function json-tool)))
      (is (= "get_weather" (get-in json-tool [:function :name])))
      (is (= "Get the current weather for a location" 
             (get-in json-tool [:function :description])))
      (is (map? (get-in json-tool [:function :parameters])))))
  
  (testing "Convert multiple tools to JSON Schema"
    (let [json-tools (tools/tools->json-schema [weather-tool calculator-tool])]
      (is (vector? json-tools))
      (is (= 2 (count json-tools)))
      (is (every? #(= "function" (:type %)) json-tools)))))

;; -----------------------------------------------------------------------------
;; Integration Tests

(deftest tool-integration-test
  (testing "Complete tool workflow"
    (let [;; Create tools
          tools-list [weather-tool calculator-tool]
          
          ;; Simulate model tool calls
          tool-calls [{:id "call_1"
                       :function {:name "get_weather"
                                  :arguments {:location "Tokyo"}}}
                      {:id "call_2"
                       :function {:name "calculate"
                                  :arguments {:operation "add" :a 10 :b 5}}}]
          
          ;; Execute tool calls
          results (tools/execute-tool-calls tools-list tool-calls)]
      
      ;; Verify results
      (is (= 2 (count results)))
      (is (every? #(= :tool (:role %)) results))
      (is (every? :tool-call-id results))
      (is (every? :content results))
      (is (not-any? :is-error results)))))

