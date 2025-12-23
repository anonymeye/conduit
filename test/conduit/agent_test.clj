(ns conduit.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [conduit.agent :as agent]
            [conduit.tools :as tools]
            [conduit.providers.mock :as mock]))

;; -----------------------------------------------------------------------------
;; Test Tools

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

(def weather-tool
  (tools/tool "get_weather"
              "Get the current weather for a location"
              [:map [:location :string]]
              (fn [{:keys [location]}]
                {:temperature 22
                 :location location
                 :conditions "sunny"})))

(def counter-tool
  (tools/tool "increment"
              "Increment a counter"
              [:map [:value :int]]
              (fn [{:keys [value]}]
                (inc value))))

;; -----------------------------------------------------------------------------
;; Mock Models for Testing

(defn make-tool-calling-mock
  "Create a mock model that returns specific tool calls"
  [tool-calls]
  (mock/model {:response (mock/tool-response tool-calls)}))

(defn make-multi-turn-mock
  "Create a mock that returns tool calls on first call, text on second"
  [tool-calls final-text]
  (let [call-count (atom 0)]
    (mock/model {:response (fn [_ _]
                                 (swap! call-count inc)
                                 (if (= 1 @call-count)
                                   (mock/tool-response tool-calls)
                                   (mock/text-response final-text)))})))

;; -----------------------------------------------------------------------------
;; Tool Loop Tests

(deftest tool-loop-basic-test
  (testing "Tool loop with single tool call"
    (let [model (make-multi-turn-mock
                  [{:name "calculate"
                    :arguments {:operation "add" :a 2 :b 3}}]
                  "The result is 5")
          result (agent/tool-loop model
                                  [{:role :user :content "Add 2 and 3"}]
                                  {:tools [calculator-tool]
                                   :max-iterations 5})]
      (is (map? result))
      (is (contains? result :response))
      (is (contains? result :messages))
      (is (contains? result :iterations))
      (is (contains? result :tool-calls-made))
      (is (= 2 (:iterations result)))
      (is (= 1 (count (:tool-calls-made result))))))
  
  (testing "Tool loop without tool calls"
    (let [model (mock/model {:response (mock/text-response "Hello!")})
          result (agent/tool-loop model
                                  [{:role :user :content "Say hello"}]
                                  {:tools [calculator-tool]
                                   :max-iterations 5})]
      (is (= 1 (:iterations result)))
      (is (empty? (:tool-calls-made result)))))
  
  (testing "Tool loop with multiple tool calls"
    (let [model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 2 :b 3}}
                   {:name "calculate" :arguments {:operation "multiply" :a 4 :b 5}}]
                  "Results: 5 and 20")
          result (agent/tool-loop model
                                  [{:role :user :content "Calculate"}]
                                  {:tools [calculator-tool]
                                   :max-iterations 5})]
      (is (= 2 (:iterations result)))
      (is (= 2 (count (:tool-calls-made result))))))
  
  (testing "Tool loop reaches max iterations"
    (let [;; Model that always returns tool calls
          model (mock/model {:response (mock/tool-response
                                         [{:name "increment" :arguments {:value 1}}])})]
      (try
        (agent/tool-loop model
                        [{:role :user :content "Keep incrementing"}]
                        {:tools [counter-tool]
                         :max-iterations 3})
        (is false "Should have thrown max-iterations error")
        (catch clojure.lang.ExceptionInfo e
          (is (= :max-iterations (:type (ex-data e))))
          (is (= 3 (:iteration (ex-data e))))))))
  
  (testing "Tool loop with unknown tool"
    (let [model (make-multi-turn-mock
                  [{:name "unknown_tool" :arguments {}}]
                  "Tool not found")
          result (agent/tool-loop model
                                  [{:role :user :content "Use unknown tool"}]
                                  {:tools [calculator-tool]
                                   :max-iterations 5})]
      (is (= 2 (:iterations result)))
      ;; Should still complete, with error in tool result
      (is (some #(= :tool (:role %)) (:messages result))))))

;; -----------------------------------------------------------------------------
;; Callback Tests

(deftest tool-loop-callbacks-test
  (testing "on-response callback"
    (let [responses (atom [])
          model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 1 :b 1}}]
                  "Result is 2")
          _ (agent/tool-loop model
                             [{:role :user :content "Add 1 and 1"}]
                             {:tools [calculator-tool]
                              :max-iterations 5
                              :on-response (fn [resp iter]
                                             (swap! responses conj {:response resp
                                                                    :iteration iter}))})]
      (is (= 2 (count @responses)))
      (is (= 0 (:iteration (first @responses))))
      (is (= 1 (:iteration (second @responses))))))
  
  (testing "on-tool-call callback"
    (let [tool-calls (atom [])
          model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 2 :b 3}}]
                  "Result is 5")
          _ (agent/tool-loop model
                             [{:role :user :content "Calculate"}]
                             {:tools [calculator-tool]
                              :max-iterations 5
                              :on-tool-call (fn [tc]
                                              (swap! tool-calls conj tc))})]
      (is (= 1 (count @tool-calls)))
      (is (= "calculate" (get-in (first @tool-calls) [:function :name])))))
  
  (testing "on-tool-result callback"
    (let [results (atom [])
          model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 2 :b 3}}]
                  "Result is 5")
          _ (agent/tool-loop model
                             [{:role :user :content "Calculate"}]
                             {:tools [calculator-tool]
                              :max-iterations 5
                              :on-tool-result (fn [tc result]
                                                (swap! results conj {:call tc
                                                                     :result result}))})]
      (is (= 1 (count @results)))
      (is (= 5 (:result (first @results)))))))

;; -----------------------------------------------------------------------------
;; Streaming Tool Loop Tests

(deftest streaming-tool-loop-test
  (testing "Streaming tool loop basic"
    (let [model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 2 :b 3}}]
                  "The result is 5")
          result (agent/streaming-tool-loop model
                                            [{:role :user :content "Add 2 and 3"}]
                                            {:tools [calculator-tool]
                                             :max-iterations 5})]
      (is (map? result))
      (is (= 2 (:iterations result)))
      (is (= 1 (count (:tool-calls-made result))))))
  
  (testing "Streaming with on-stream-event callback"
    (let [events (atom [])
          model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 2 :b 3}}]
                  "Result is 5")
          result (agent/streaming-tool-loop model
                                            [{:role :user :content "Calculate"}]
                                            {:tools [calculator-tool]
                                             :max-iterations 5
                                             :on-stream-event (fn [event]
                                                                (swap! events conj event))})]
      (is (= 2 (:iterations result)))
      ;; Should have received stream events
      (is (pos? (count @events))))))

;; -----------------------------------------------------------------------------
;; Simple Agent Tests

(deftest make-agent-test
  (testing "Create and use simple agent"
    (let [model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 5 :b 10}}]
                  "The sum is 15")
          my-agent (agent/make-agent model [calculator-tool]
                                     :system-message "You are a calculator."
                                     :max-iterations 5)
          result (my-agent "What is 5 plus 10?")]
      (is (map? result))
      (is (contains? result :response))
      (is (= 2 (:iterations result)))))
  
  (testing "Agent without system message"
    (let [model (mock/model {:response (mock/text-response "Hello!")})
          my-agent (agent/make-agent model [calculator-tool]
                                     :max-iterations 5)
          result (my-agent "Say hello")]
      (is (= 1 (:iterations result)))))
  
  (testing "Agent with chat options"
    (let [model (mock/model {:response (mock/text-response "Response")})
          my-agent (agent/make-agent model [calculator-tool]
                                     :max-iterations 5
                                     :chat-opts {:temperature 0.5})
          result (my-agent "Test")]
      (is (some? result)))))

;; -----------------------------------------------------------------------------
;; Stateful Agent Tests

(deftest stateful-agent-test
  (testing "Create stateful agent"
    (let [model (mock/model {:response (mock/text-response "Hello!")})
          my-agent (agent/stateful-agent model [calculator-tool]
                                         :system-message "You are helpful.")]
      (is (map? my-agent))
      (is (contains? my-agent :send!))
      (is (contains? my-agent :history))
      (is (contains? my-agent :reset!))
      (is (= 1 (count @(:history my-agent))))))
  
  (testing "Send messages and maintain history"
    (let [model (mock/model {:response (mock/text-response "Response")})
          my-agent (agent/stateful-agent model [calculator-tool]
                                         :system-message "System")
          _ ((:send! my-agent) "First message")
          _ ((:send! my-agent) "Second message")]
      ;; Should have: system + user1 + assistant1 + user2 + assistant2
      (is (= 5 (count @(:history my-agent))))))
  
  (testing "Reset agent history"
    (let [model (mock/model {:response (mock/text-response "Response")})
          my-agent (agent/stateful-agent model [calculator-tool]
                                         :system-message "System")
          _ ((:send! my-agent) "Message")
          _ ((:reset! my-agent))]
      ;; Should only have system message after reset
      (is (= 1 (count @(:history my-agent))))))
  
  (testing "Stateful agent with tool calls"
    (let [model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "add" :a 1 :b 2}}]
                  "The sum is 3")
          my-agent (agent/stateful-agent model [calculator-tool]
                                         :max-iterations 5)
          result ((:send! my-agent) "Add 1 and 2")]
      (is (= 2 (:iterations result)))
      ;; History should include: user + assistant + tool + assistant
      (is (= 4 (count @(:history my-agent))))))
  
  (testing "Stateful agent without system message"
    (let [model (mock/model {:response (mock/text-response "Hi")})
          my-agent (agent/stateful-agent model [calculator-tool])
          _ ((:send! my-agent) "Hello")]
      ;; Should have: user + assistant
      (is (= 2 (count @(:history my-agent)))))))

;; -----------------------------------------------------------------------------
;; Integration Tests

(deftest agent-integration-test
  (testing "Multi-turn conversation with tools"
    (let [call-count (atom 0)
          model (mock/model 
                  {:response (fn [_messages _opts]
                                  (swap! call-count inc)
                                  (cond
                                    (= 1 @call-count)
                                    (mock/tool-response 
                                      [{:name "get_weather" :arguments {:location "Tokyo"}}])
                                    
                                    (= 2 @call-count)
                                    (mock/text-response "The weather in Tokyo is sunny, 22°C")
                                    
                                    :else
                                    (mock/text-response "Done")))})
          result (agent/tool-loop model
                                  [{:role :user :content "What's the weather in Tokyo?"}]
                                  {:tools [weather-tool]
                                   :max-iterations 10})]
      (is (= 2 (:iterations result)))
      (is (= 1 (count (:tool-calls-made result))))
      (is (some #(= :tool (:role %)) (:messages result)))))
  
  (testing "Agent with multiple tools"
    (let [model (make-multi-turn-mock
                  [{:name "get_weather" :arguments {:location "Paris"}}
                   {:name "calculate" :arguments {:operation "add" :a 10 :b 5}}]
                  "Weather is sunny, calculation result is 15")
          result (agent/tool-loop model
                                  [{:role :user :content "Get weather and calculate"}]
                                  {:tools [weather-tool calculator-tool]
                                   :max-iterations 10})]
      (is (= 2 (:iterations result)))
      (is (= 2 (count (:tool-calls-made result))))))
  
  (testing "Complete agent workflow"
    (let [model (make-multi-turn-mock
                  [{:name "calculate" :arguments {:operation "multiply" :a 7 :b 8}}]
                  "7 times 8 equals 56")
          my-agent (agent/make-agent model [calculator-tool]
                                     :system-message "You are a math assistant."
                                     :max-iterations 5)
          result (my-agent "What is 7 times 8?")]
      (is (= "7 times 8 equals 56" (get-in result [:response :content])))
      (is (= 2 (:iterations result)))
      (is (= 1 (count (:tool-calls-made result)))))))

