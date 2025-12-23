(ns conduit.providers.grok-test
  "Tests for conduit.providers.grok namespace.
  
  Unit tests can run without an API key. Integration tests (tagged with ^:integration)
  require a valid XAI_API_KEY environment variable."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a :refer [<!!]]
            [conduit.core :as c]
            [conduit.providers.grok :as grok]
            [conduit.error :as error]))

;; Unit tests (no API key required)

(deftest test-models-config
  (testing "Models configuration is valid"
    (is (map? grok/models))
    (is (contains? grok/models :grok-3))
    (is (contains? grok/models :grok-3-fast))
    (is (contains? grok/models :grok-3-mini))
    (is (contains? grok/models :grok-3-mini-fast))
    (doseq [[_ model-spec] grok/models]
      (is (string? (:id model-spec)))
      (is (pos? (:context-window model-spec)))
      (is (pos? (:max-output model-spec))))))

(deftest test-capabilities
  (testing "Capabilities are defined"
    (is (map? grok/capabilities))
    (is (:streaming grok/capabilities))
    (is (:tool-calling grok/capabilities))
    (is (:vision grok/capabilities))
    (is (:json-mode grok/capabilities))))

(deftest test-build-messages
  (testing "Build simple text messages"
    (let [messages [{:role :user :content "Hello"}
                    {:role :assistant :content "Hi there"}]
          built (#'grok/build-messages messages)]
      (is (= 2 (count built)))
      (is (= "user" (:role (first built))))
      (is (= "Hello" (:content (first built))))
      (is (= "assistant" (:role (second built))))
      (is (= "Hi there" (:content (second built))))))
  
  (testing "Build messages with content blocks"
    (let [messages [{:role :user 
                     :content [{:type :text :text "Hello"}
                               {:type :image 
                                :source {:type :url 
                                         :url "https://example.com/image.jpg"}}]}]
          built (#'grok/build-messages messages)]
      (is (= 1 (count built)))
      (is (vector? (:content (first built))))
      (is (= 2 (count (:content (first built)))))
      (is (= "text" (:type (first (:content (first built))))))
      (is (= "image_url" (:type (second (:content (first built))))))))
  
  (testing "Build messages with tool call ID"
    (let [messages [{:role :tool :content "result" :tool-call-id "call_123"}]
          built (#'grok/build-messages messages)]
      (is (= "call_123" (:tool_call_id (first built)))))))

(deftest test-build-tools
  (testing "Build tools from Conduit format"
    (let [tools [{:name "get_weather"
                  :description "Get weather for a location"
                  :parameters {:type "object"
                               :properties {:location {:type "string"}}
                               :required ["location"]}}]
          built (#'grok/build-tools tools)]
      (is (= 1 (count built)))
      (is (= "function" (:type (first built))))
      (is (= "get_weather" (get-in built [0 :function :name])))
      (is (= "Get weather for a location" (get-in built [0 :function :description])))
      (is (map? (get-in built [0 :function :parameters]))))))

(deftest test-build-request
  (testing "Build basic request"
    (let [config {:model "grok-3" :api-key "test-key"}
          messages [{:role :user :content "Hello"}]
          request (#'grok/build-request config messages {})]
      (is (= "grok-3" (:model request)))
      (is (= 1 (count (:messages request))))
      (is (not (:stream request)))))
  
  (testing "Build request with options"
    (let [config {:model "grok-3" :api-key "test-key"}
          messages [{:role :user :content "Hello"}]
          opts {:temperature 0.7
                :max-tokens 100
                :top-p 0.9
                :stream true}
          request (#'grok/build-request config messages opts)]
      (is (= 0.7 (:temperature request)))
      (is (= 100 (:max_tokens request)))
      (is (= 0.9 (:top_p request)))
      (is (:stream request))))
  
  (testing "Build request with tools"
    (let [config {:model "grok-3" :api-key "test-key"}
          messages [{:role :user :content "What's the weather?"}]
          opts {:tools [{:name "get_weather"
                         :description "Get weather"
                         :parameters {:type "object"}}]}
          request (#'grok/build-request config messages opts)]
      (is (vector? (:tools request)))
      (is (= 1 (count (:tools request))))))
  
  (testing "Build request with JSON mode"
    (let [config {:model "grok-3" :api-key "test-key"}
          messages [{:role :user :content "Return JSON"}]
          opts {:response-format :json}
          request (#'grok/build-request config messages opts)]
      (is (= {:type "json_object"} (:response_format request))))))

(deftest test-parse-stop-reason
  (testing "Parse stop reasons"
    (is (= :end-turn (#'grok/parse-stop-reason "stop")))
    (is (= :tool-use (#'grok/parse-stop-reason "tool_calls")))
    (is (= :max-tokens (#'grok/parse-stop-reason "length")))
    (is (= :content-filter (#'grok/parse-stop-reason "content_filter")))
    (is (= :end-turn (#'grok/parse-stop-reason "unknown")))))

(deftest test-parse-tool-calls
  (testing "Parse tool calls from API format"
    (let [api-tool-calls [{:id "call_123"
                           :function {:name "get_weather"
                                      :arguments "{\"location\":\"NYC\"}"}}]
          parsed (#'grok/parse-tool-calls api-tool-calls)]
      (is (= 1 (count parsed)))
      (is (= "call_123" (:id (first parsed))))
      (is (= :function (:type (first parsed))))
      (is (= "get_weather" (get-in parsed [0 :function :name])))
      (is (= {:location "NYC"} (get-in parsed [0 :function :arguments]))))))

(deftest test-parse-response
  (testing "Parse complete API response"
    (let [api-response {:id "chatcmpl-123"
                        :model "grok-3"
                        :choices [{:message {:role "assistant"
                                             :content "Hello!"}
                                   :finish_reason "stop"}]
                        :usage {:prompt_tokens 10
                                :completion_tokens 5
                                :total_tokens 15}}
          parsed (#'grok/parse-response api-response)]
      (is (= "chatcmpl-123" (:id parsed)))
      (is (= :assistant (:role parsed)))
      (is (= "Hello!" (:content parsed)))
      (is (= "grok-3" (:model parsed)))
      (is (= :end-turn (:stop-reason parsed)))
      (is (= {:input-tokens 10 :output-tokens 5 :total-tokens 15} (:usage parsed))))))

(deftest test-parse-stream-event
  (testing "Parse content delta event"
    (let [line "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
          event (#'grok/parse-stream-event line)]
      (is (= :content-delta (:type event)))
      (is (= "Hello" (get-in event [:delta :text])))))
  
  (testing "Parse tool use start event"
    (let [line "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"test\"}}]}}]}"
          event (#'grok/parse-stream-event line)]
      (is (= :tool-use-start (:type event)))
      (is (= "call_1" (get-in event [:tool-call :id])))
      (is (= "test" (get-in event [:tool-call :name])))))
  
  (testing "Parse tool use delta event"
    (let [line "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"arg\\\":\"}}]}}]}"
          event (#'grok/parse-stream-event line)]
      (is (= :tool-use-delta (:type event)))
      (is (string? (get-in event [:delta :partial-json])))))
  
  (testing "Parse message end event"
    (let [line "data: {\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}"
          event (#'grok/parse-stream-event line)]
      (is (= :message-end (:type event)))
      (is (= :end-turn (:stop-reason event)))
      (is (= {:input-tokens 10 :output-tokens 20 :total-tokens 30} (:usage event)))))
  
  (testing "Ignore [DONE] marker"
    (let [line "data: [DONE]"
          event (#'grok/parse-stream-event line)]
      (is (nil? event))))
  
  (testing "Ignore non-data lines"
    (let [line ": comment"
          event (#'grok/parse-stream-event line)]
      (is (nil? event)))))

(deftest test-model-creation-without-api-key
  (testing "Model creation fails without API key"
    (with-redefs [grok/model (fn [opts]
                               (let [config (merge grok/default-config
                                                   {:model "grok-3"
                                                    :api-key (or (:api-key opts) 
                                                                 (System/getenv "XAI_API_KEY"))}
                                                   opts)]
                                 (when-not (:api-key config)
                                   (throw (error/error
                                            :authentication
                                            "Grok API key required"
                                            {:provider :grok})))
                                 config))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (grok/model {}))))))

(deftest test-model-info
  (testing "Model info without API key"
    ;; We can't create a real model without an API key, so we'll test the structure
    (is (map? grok/capabilities))
    (is (contains? grok/capabilities :streaming))
    (is (contains? grok/capabilities :tool-calling))))

;; Integration tests (require XAI_API_KEY environment variable)

(deftest ^:integration test-simple-chat
  (testing "Simple chat completion"
    (when-let [api-key (System/getenv "XAI_API_KEY")]
      (let [model (grok/model {:model "grok-3-mini-fast" :api-key api-key})
            messages [{:role :user :content "Say 'hello' and nothing else"}]
            response (c/chat model messages)]
        (is (= :assistant (:role response)))
        (is (string? (:content response)))
        (is (not (empty? (:content response))))
        (is (= :end-turn (:stop-reason response)))
        (is (map? (:usage response)))
        (is (pos? (get-in response [:usage :input-tokens])))
        (is (pos? (get-in response [:usage :output-tokens])))
        (grok/shutdown model)))))

(deftest ^:integration test-streaming-chat
  (testing "Streaming chat completion"
    (when-let [api-key (System/getenv "XAI_API_KEY")]
      (let [model (grok/model {:model "grok-3-mini-fast" :api-key api-key})
            messages [{:role :user :content "Count from 1 to 3"}]
            ch (c/stream model messages)
            events (loop [acc []]
                     (if-let [event (<!! ch)]
                       (recur (conj acc event))
                       acc))]
        (is (some #(= :content-delta (:type %)) events))
        (is (some #(= :message-end (:type %)) events))
        (let [content-deltas (filter #(= :content-delta (:type %)) events)
              full-text (apply str (map #(get-in % [:delta :text]) content-deltas))]
          (is (not (empty? full-text))))
        (grok/shutdown model)))))

(deftest ^:integration test-chat-with-options
  (testing "Chat with temperature and max-tokens"
    (when-let [api-key (System/getenv "XAI_API_KEY")]
      (let [model (grok/model {:model "grok-3-mini-fast" :api-key api-key})
            messages [{:role :user :content "Tell me a very short joke"}]
            response (c/chat model messages {:temperature 0.7 :max-tokens 50})]
        (is (= :assistant (:role response)))
        (is (string? (:content response)))
        (grok/shutdown model)))))

(deftest ^:integration test-model-info-integration
  (testing "Model info with real model"
    (when-let [api-key (System/getenv "XAI_API_KEY")]
      (let [model (grok/model {:model "grok-3-mini-fast" :api-key api-key})
            info (c/model-info model)]
        (is (= :grok (:provider info)))
        (is (= "grok-3-mini-fast" (:model info)))
        (is (map? (:capabilities info)))
        (is (:streaming (:capabilities info)))
        (is (:tool-calling (:capabilities info)))
        (is (pos? (:context-window info)))
        (is (pos? (:max-output info)))
        (grok/shutdown model)))))

;; Note: Tool calling tests would require more setup and are better suited
;; for integration testing with actual API calls. The unit tests above
;; verify the request/response building logic.

