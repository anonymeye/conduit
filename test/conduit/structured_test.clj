(ns conduit.structured-test
  (:require [clojure.test :refer [deftest is testing]]
            [conduit.structured :as structured]
            [conduit.providers.mock :as mock]))

;; -----------------------------------------------------------------------------
;; Test Schemas

(def person-schema
  [:map
   [:name :string]
   [:age :int]
   [:email {:optional true} :string]])

(def entities-schema
  [:map
   [:people [:vector :string]]
   [:places [:vector :string]]
   [:dates [:vector :string]]])

(def sentiment-schema
  [:map
   [:sentiment [:enum "positive" "negative" "neutral"]]
   [:confidence {:optional true} [:double {:min 0 :max 1}]]])

;; -----------------------------------------------------------------------------
;; Mock Models for Testing

(defn make-structured-mock
  "Create a mock model that returns structured output via tool call"
  [tool-name output-data]
  (mock/model 
    {:response (mock/tool-response 
                 [{:name tool-name
                   :arguments output-data}])}))

(defn make-non-compliant-mock
  "Create a mock that doesn't use the output tool"
  []
  (mock/model {:response (mock/text-response "I won't use the tool")}))

;; -----------------------------------------------------------------------------
;; Structured Output Tests

(deftest with-structured-output-test
  (testing "Basic structured output"
    (let [model (make-structured-mock "output" 
                                      {:name "John Doe" :age 30})
          extract-fn (structured/with-structured-output model person-schema)
          result (extract-fn [{:role :user :content "Extract person info"}])]
      (is (map? result))
      (is (= "John Doe" (:name result)))
      (is (= 30 (:age result)))))
  
  (testing "Structured output with custom tool name"
    (let [model (make-structured-mock "extract_person"
                                      {:name "Jane Smith" :age 25})
          extract-fn (structured/with-structured-output model person-schema
                       :name "extract_person"
                       :description "Extract person information")
          result (extract-fn [{:role :user :content "Get person"}])]
      (is (= "Jane Smith" (:name result)))
      (is (= 25 (:age result)))))
  
  (testing "Structured output with optional fields"
    (let [model (make-structured-mock "output"
                                      {:name "Bob" :age 40 :email "bob@example.com"})
          extract-fn (structured/with-structured-output model person-schema)
          result (extract-fn [{:role :user :content "Extract"}])]
      (is (= "bob@example.com" (:email result)))))
  
  (testing "Strict mode - model doesn't use tool"
    (let [model (make-non-compliant-mock)
          extract-fn (structured/with-structured-output model person-schema
                       :strict true)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Model did not use output tool"
                            (extract-fn [{:role :user :content "Extract"}])))))
  
  (testing "Non-strict mode - model doesn't use tool"
    (let [model (make-non-compliant-mock)
          extract-fn (structured/with-structured-output model person-schema
                       :strict false)
          result (extract-fn [{:role :user :content "Extract"}])]
      (is (map? result))
      (is (contains? result :content))))
  
  (testing "Complex nested schema"
    (let [model (make-structured-mock "output"
                                      {:people ["Alice" "Bob"]
                                       :places ["New York" "Paris"]
                                       :dates ["2024-01-01"]})
          extract-fn (structured/with-structured-output model entities-schema)
          result (extract-fn [{:role :user :content "Extract entities"}])]
      (is (= ["Alice" "Bob"] (:people result)))
      (is (= ["New York" "Paris"] (:places result)))
      (is (= ["2024-01-01"] (:dates result))))))

;; -----------------------------------------------------------------------------
;; JSON Mode Tests

(deftest json-mode-test
  (testing "JSON mode basic"
    (let [model (make-structured-mock "json_output"
                                      {:sentiment "positive" :confidence 0.95})
          extract-fn (structured/json-mode model sentiment-schema)
          result (extract-fn [{:role :user :content "Analyze sentiment"}])]
      (is (= "positive" (:sentiment result)))
      (is (= 0.95 (:confidence result)))))
  
  (testing "JSON mode with custom name"
    (let [model (make-structured-mock "custom_json"
                                      {:sentiment "negative"})
          extract-fn (structured/json-mode model sentiment-schema
                       :name "custom_json")
          result (extract-fn [{:role :user :content "Analyze"}])]
      (is (= "negative" (:sentiment result))))))

;; -----------------------------------------------------------------------------
;; Extract with Schema Tests

(deftest extract-with-schema-test
  (testing "Extract from text"
    (let [model (make-structured-mock "extract"
                                      {:name "Alice Johnson" :age 28})
          result (structured/extract-with-schema 
                   model
                   "Alice Johnson is 28 years old"
                   person-schema)]
      (is (= "Alice Johnson" (:name result)))
      (is (= 28 (:age result)))))
  
  (testing "Extract with system prompt"
    (let [model (make-structured-mock "extract"
                                      {:people ["John" "Mary"]
                                       :places ["London"]
                                       :dates []})
          result (structured/extract-with-schema
                   model
                   "John and Mary visited London"
                   entities-schema
                   :system-prompt "Extract all entities from the text")]
      (is (= ["John" "Mary"] (:people result)))
      (is (= ["London"] (:places result)))))
  
  (testing "Extract with custom tool name and description"
    (let [model (make-structured-mock "get_entities"
                                      {:people ["Bob"]
                                       :places ["Paris"]
                                       :dates ["2024-03-15"]})
          result (structured/extract-with-schema
                   model
                   "Bob went to Paris on 2024-03-15"
                   entities-schema
                   :name "get_entities"
                   :description "Get all entities")]
      (is (= ["Bob"] (:people result)))
      (is (= ["Paris"] (:places result)))
      (is (= ["2024-03-15"] (:dates result))))))

;; -----------------------------------------------------------------------------
;; Classification Tests

(deftest classify-test
  (testing "Classify with string categories"
    (let [model (make-structured-mock "classify"
                                      {:category :positive})
          result (structured/classify model
                                      "This is amazing!"
                                      ["positive" "negative" "neutral"])]
      (is (= "positive" (:category result)))))
  
  (testing "Classify with map categories"
    (let [model (make-structured-mock "classify"
                                      {:category :weather})
          categories [{:name "weather" :description "About weather"}
                      {:name "sports" :description "About sports"}
                      {:name "other" :description "Other topics"}]
          result (structured/classify model
                                      "It's sunny today"
                                      categories)]
      (is (= "weather" (:category result)))))
  
  (testing "Classify with reasoning"
    (let [model (make-structured-mock "classify"
                                      {:category :negative
                                       :reasoning "Contains complaint"})
          result (structured/classify model
                                      "This product is terrible"
                                      ["positive" "negative" "neutral"])]
      (is (= "negative" (:category result)))
      (is (= "Contains complaint" (:reasoning result)))))
  
  (testing "Classify with custom system prompt"
    (let [model (make-structured-mock "classify"
                                      {:category :technical})
          result (structured/classify model
                                      "API endpoint documentation"
                                      ["technical" "business" "personal"]
                                      :system-prompt "Classify the document type")]
      (is (= "technical" (:category result))))))

;; -----------------------------------------------------------------------------
;; Validation Tests

(deftest validation-test
  (testing "Validate structured output"
    (let [valid-data {:name "John" :age 30}
          result (structured/validate-structured-output person-schema valid-data)]
      (is (= valid-data result))))
  
  (testing "Validate fails on invalid data"
    (let [invalid-data {:name "John" :age "thirty"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (structured/validate-structured-output person-schema invalid-data)))))
  
  (testing "Validate fails on missing required field"
    (let [invalid-data {:name "John"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (structured/validate-structured-output person-schema invalid-data)))))
  
  (testing "Coerce structured output"
    (let [data {:name "John" :age 30}
          result (structured/coerce-structured-output person-schema data)]
      (is (= data result)))))

;; -----------------------------------------------------------------------------
;; Integration Tests

(deftest structured-integration-test
  (testing "Complete extraction workflow"
    (let [model (make-structured-mock "extract"
                                      {:people ["Alice" "Bob" "Charlie"]
                                       :places ["Tokyo" "London"]
                                       :dates ["2024-01-15" "2024-02-20"]})
          text "Alice, Bob, and Charlie traveled to Tokyo and London on 2024-01-15 and 2024-02-20"
          result (structured/extract-with-schema model text entities-schema)]
      (is (= 3 (count (:people result))))
      (is (= 2 (count (:places result))))
      (is (= 2 (count (:dates result))))))
  
  (testing "Multiple extractions with same model"
    (let [call-count (atom 0)
          model (mock/model
                  {:response (fn [_messages _opts]
                                  (swap! call-count inc)
                                  (if (= 1 @call-count)
                                    (mock/tool-response
                                      [{:id "call_1"
                                        :name "extract"
                                        :arguments {:name "Alice" :age 25}}])
                                    (mock/tool-response
                                      [{:id "call_2"
                                        :name "extract"
                                        :arguments {:name "Bob" :age 30}}])))})
          result1 (structured/extract-with-schema model "Alice is 25" person-schema)
          result2 (structured/extract-with-schema model "Bob is 30" person-schema)]
      (is (= "Alice" (:name result1)))
      (is (= "Bob" (:name result2)))))
  
  (testing "Classification pipeline"
    (let [texts ["This is great!" "This is terrible" "It's okay"]
          model (mock/model
                  {:response (fn [messages opts]
                                  (let [content (:content (last (filter #(= :user (:role %)) messages)))]
                                    (mock/tool-response
                                      [{:id "call_1"
                                        :name "classify"
                                        :arguments {:category (cond
                                                                (re-find #"great" content) :positive
                                                                (re-find #"terrible" content) :negative
                                                                :else :neutral)}}])))})
          results (mapv #(structured/classify model % ["positive" "negative" "neutral"])
                        texts)]
      (is (= 3 (count results)))
      (is (= "positive" (:category (first results))))
      (is (= "negative" (:category (second results))))
      (is (= "neutral" (:category (nth results 2))))))
  
  (testing "Structured output with validation"
    (let [model (make-structured-mock "output"
                                      {:name "Valid User" :age 25})
          extract-fn (structured/with-structured-output model person-schema)
          result (extract-fn [{:role :user :content "Extract"}])
          validated (structured/validate-structured-output person-schema result)]
      (is (= result validated)))))

;; -----------------------------------------------------------------------------
;; Error Handling Tests

(deftest error-handling-test
  (testing "Handle model error gracefully"
    (let [model (mock/model {:response (fn [_ _] (throw (ex-info "Model error" {})))})
          extract-fn (structured/with-structured-output model person-schema)]
      (is (thrown? Exception
                   (extract-fn [{:role :user :content "Extract"}]))))))

