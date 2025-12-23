(ns conduit.core-interceptor-test
  "Integration tests for interceptor support in conduit.core"
  (:require [clojure.test :refer [deftest is testing]]
            [conduit.core :as c]
            [conduit.interceptor :as int]))

;;; -----------------------------------------------------------------------------
;;; Mock ChatModel for Testing

(defrecord MockModel [responses]
  c/ChatModel
  
  (chat [_this messages]
    (c/chat _this messages {}))
  
  (chat [_this _messages opts]
    ;; Return next response from atom
    (let [response (first @responses)]
      (swap! responses rest)
      (merge {:role :assistant
              :content "Mock response"
              :usage {:input-tokens 10
                     :output-tokens 20
                     :total-tokens 30}}
             opts ; Include opts for testing
             response))) ; Response overrides defaults
  
  (stream [_this _messages]
    (throw (ex-info "Streaming not implemented" {:type :not-implemented})))
  
  (stream [_this _messages _opts]
    (throw (ex-info "Streaming not implemented" {:type :not-implemented})))
  
  (model-info [_this]
    {:provider :mock
     :model "mock-model"
     :capabilities {:streaming false
                   :tool-calling true
                   :vision false
                   :json-mode false
                   :max-context 4096}}))

(defn mock-model
  "Create a mock model with predefined responses"
  ([]
   (mock-model []))
  ([responses]
   (->MockModel (atom responses))))

;;; -----------------------------------------------------------------------------
;;; Basic Integration Tests

(deftest test-chat-with-interceptors-empty-chain
  (testing "Empty interceptor chain works like normal chat"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          response (c/chat-with-interceptors model messages [])]
      (is (= :assistant (:role response)))
      (is (= "Mock response" (:content response)))
      (is (= 30 (c/total-tokens response))))))

(deftest test-chat-with-interceptors-single-interceptor
  (testing "Single interceptor enter and leave"
    (let [events (atom [])
          model (mock-model)
          messages [{:role :user :content "Hello"}]
          logging-int (int/interceptor
                       {:name :logging
                        :enter (fn [ctx]
                                 (swap! events conj :enter)
                                 ctx)
                        :leave (fn [ctx]
                                 (swap! events conj :leave)
                                 ctx)})
          response (c/chat-with-interceptors model messages [logging-int])]
      (is (= [:enter :leave] @events))
      (is (= "Mock response" (:content response))))))

(deftest test-chat-with-interceptors-multiple
  (testing "Multiple interceptors execute in order"
    (let [order (atom [])
          model (mock-model)
          messages [{:role :user :content "Hello"}]
          int-1 (int/interceptor
                 {:name :first
                  :enter (fn [ctx]
                           (swap! order conj :first-enter)
                           ctx)
                  :leave (fn [ctx]
                           (swap! order conj :first-leave)
                           ctx)})
          int-2 (int/interceptor
                 {:name :second
                  :enter (fn [ctx]
                           (swap! order conj :second-enter)
                           ctx)
                  :leave (fn [ctx]
                           (swap! order conj :second-leave)
                           ctx)})
          response (c/chat-with-interceptors model messages [int-1 int-2])]
      (is (= [:first-enter :second-enter :second-leave :first-leave] @order))
      (is (some? response)))))

;;; -----------------------------------------------------------------------------
;;; Message Transformation Tests

(deftest test-transform-messages
  (testing "Interceptor can transform messages"
    (let [model (mock-model)
          messages [{:role :user :content "Original"}]
          transform-int (int/interceptor
                         {:name :transform
                          :enter (fn [ctx]
                                   (assoc ctx :transformed-messages
                                          [{:role :user :content "Transformed"}]))})
          response (c/chat-with-interceptors model messages [transform-int])]
      ;; Model should have received transformed messages
      (is (some? response)))))

(deftest test-transform-opts
  (testing "Interceptor can transform options"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          transform-int (int/interceptor
                         {:name :add-temp
                          :enter (fn [ctx]
                                   (assoc ctx :transformed-opts
                                          {:temperature 0.9}))})
          response (c/chat-with-interceptors model messages [transform-int] {:temperature 0.7})]
      ;; Response should include the transformed opts
      (is (= 0.9 (:temperature response))))))

(deftest test-transform-response
  (testing "Interceptor can transform response in leave phase"
    (let [model (mock-model [{:content "Original content"}])
          messages [{:role :user :content "Hello"}]
          transform-int (int/interceptor
                         {:name :add-metadata
                          :leave (fn [ctx]
                                   (assoc-in ctx [:response :metadata]
                                             {:processed true}))})
          response (c/chat-with-interceptors model messages [transform-int])]
      (is (= "Original content" (:content response)))
      (is (= {:processed true} (:metadata response))))))

;;; -----------------------------------------------------------------------------
;;; Early Termination Tests

(deftest test-cache-termination
  (testing "Interceptor can terminate early (cache hit)"
    (let [model (mock-model) ; Won't be called
          messages [{:role :user :content "Hello"}]
          cache-int (int/interceptor
                     {:name :cache
                      :enter (fn [ctx]
                               ;; Simulate cache hit
                               (-> ctx
                                   (assoc :response {:role :assistant
                                                    :content "Cached response"
                                                    :cached true})
                                   int/terminate))})
          response (c/chat-with-interceptors model messages [cache-int])]
      (is (= "Cached response" (:content response)))
      (is (true? (:cached response))))))

(deftest test-termination-skips-model
  (testing "Termination prevents model call"
    (let [model-called (atom false)
          model (reify c/ChatModel
                  (chat [_this _messages _opts]
                    (reset! model-called true)
                    {:role :assistant :content "Should not see this"}))
          messages [{:role :user :content "Hello"}]
          terminate-int (int/interceptor
                         {:name :terminate
                          :enter (fn [ctx]
                                   (-> ctx
                                       (assoc :response {:role :assistant
                                                        :content "Terminated"})
                                       int/terminate))})
          response (c/chat-with-interceptors model messages [terminate-int])]
      (is (not @model-called))
      (is (= "Terminated" (:content response))))))

;;; -----------------------------------------------------------------------------
;;; Error Handling Tests

(deftest test-error-in-enter-phase
  (testing "Error in enter phase is caught"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          error-int (int/interceptor
                     {:name :fail
                      :enter (fn [_ctx]
                               (throw (ex-info "Enter error" {:phase :enter})))})
          error (try
                  (c/chat-with-interceptors model messages [error-int])
                  nil
                  (catch Exception e e))]
      (is (some? error))
      (is (= "Enter error" (ex-message error)))
      (is (= :enter (:phase (ex-data error)))))))

(deftest test-error-handler-recovery
  (testing "Error handler can recover from error"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          fail-int (int/interceptor
                    {:name :fail
                     :enter (fn [_ctx]
                              (throw (ex-info "Test error" {})))})
          recover-int (int/interceptor
                       {:name :recover
                        :error (fn [ctx _err]
                                 ;; Recover by providing response
                                 (-> ctx
                                     int/clear-error
                                     (assoc :response {:role :assistant
                                                      :content "Recovered"})
                                     int/terminate))})
          response (c/chat-with-interceptors model messages [recover-int fail-int])]
      (is (= "Recovered" (:content response))))))

(deftest test-model-error-handling
  (testing "Model errors are caught and can be handled"
    (let [model (reify c/ChatModel
                  (chat [_this _messages _opts]
                    (throw (ex-info "Model error" {:type :api-error}))))
          messages [{:role :user :content "Hello"}]
          error-int (int/interceptor
                     {:name :handle-error
                      :error (fn [ctx _err]
                               ;; Provide fallback response
                               (-> ctx
                                   int/clear-error
                                   (assoc :response {:role :assistant
                                                    :content "Fallback response"})
                                   int/terminate))})
          response (c/chat-with-interceptors model messages [error-int])]
      (is (= "Fallback response" (:content response))))))

;;; -----------------------------------------------------------------------------
;;; Context Variant Tests

(deftest test-chat-with-interceptors-ctx
  (testing "Context variant returns full context"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          metadata-int (int/interceptor
                        {:name :add-metadata
                         :enter (fn [ctx]
                                  (assoc ctx :metadata {:request-id "123"}))
                         :leave (fn [ctx]
                                  (update ctx :metadata assoc :response-id "456"))})
          ctx (c/chat-with-interceptors-ctx model messages [metadata-int])]
      (is (= model (:model ctx)))
      (is (= messages (:messages ctx)))
      (is (= "Mock response" (get-in ctx [:response :content])))
      (is (= {:request-id "123" :response-id "456"} (:metadata ctx)))
      (is (empty? (:queue ctx)))
      (is (empty? (:stack ctx))))))

(deftest test-ctx-variant-with-transformations
  (testing "Context variant shows transformations"
    (let [model (mock-model)
          messages [{:role :user :content "Original"}]
          transform-int (int/interceptor
                         {:name :transform
                          :enter (fn [ctx]
                                   (assoc ctx :transformed-messages
                                          [{:role :user :content "Transformed"}]))})
          ctx (c/chat-with-interceptors-ctx model messages [transform-int])]
      (is (= "Original" (get-in ctx [:messages 0 :content])))
      (is (= "Transformed" (get-in ctx [:transformed-messages 0 :content]))))))

;;; -----------------------------------------------------------------------------
;;; Composition Tests

(deftest test-interceptor-composition
  (testing "Complex interceptor composition"
    (let [events (atom [])
          model (mock-model)
          messages [{:role :user :content "Hello"}]
          
          ;; Logging interceptor
          logging (int/interceptor
                   {:name :logging
                    :enter (fn [ctx]
                             (swap! events conj {:event :log-request
                                                :messages (count (:messages ctx))})
                             ctx)
                    :leave (fn [ctx]
                             (swap! events conj {:event :log-response
                                                :tokens (c/total-tokens (:response ctx))})
                             ctx)})
          
          ;; Message enrichment
          enrich (int/interceptor
                  {:name :enrich
                   :enter (fn [ctx]
                            (swap! events conj {:event :enrich})
                            (update ctx :messages
                                    #(into [{:role :system :content "You are helpful."}] %)))})
          
          ;; Response processing
          process (int/interceptor
                   {:name :process
                    :leave (fn [ctx]
                             (swap! events conj {:event :process})
                             (assoc-in ctx [:response :processed] true))})
          
          response (c/chat-with-interceptors model messages [logging enrich process])]
      
      (is (= 4 (count @events)))
      (is (= :log-request (:event (nth @events 0))))
      (is (= :enrich (:event (nth @events 1))))
      (is (= :process (:event (nth @events 2))))
      (is (= :log-response (:event (nth @events 3))))
      (is (true? (:processed response))))))

(deftest test-interceptor-chain-utility
  (testing "Using interceptor chain utility"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          int-1 (int/interceptor (fn [ctx] (assoc ctx :int-1 true)))
          int-2 (int/interceptor (fn [ctx] (assoc ctx :int-2 true)))
          chain (int/chain int-1 int-2)
          ctx (c/chat-with-interceptors-ctx model messages chain)]
      (is (true? (:int-1 ctx)))
      (is (true? (:int-2 ctx))))))

;;; -----------------------------------------------------------------------------
;;; Options Tests

(deftest test-with-opts
  (testing "Options are passed through correctly"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          opts {:temperature 0.8 :max-tokens 100}
          response (c/chat-with-interceptors model messages [] opts)]
      (is (= 0.8 (:temperature response)))
      (is (= 100 (:max-tokens response))))))

(deftest test-opts-transformation
  (testing "Interceptors can modify options"
    (let [model (mock-model)
          messages [{:role :user :content "Hello"}]
          modify-opts (int/interceptor
                       {:name :modify-opts
                        :enter (fn [ctx]
                                 (assoc ctx :transformed-opts
                                        (assoc (:opts ctx) :temperature 0.5)))})
          response (c/chat-with-interceptors model messages [modify-opts] {:temperature 0.9})]
      ;; Should use transformed opts
      (is (= 0.5 (:temperature response))))))

