(ns conduit.interceptor-test
  (:require [clojure.test :refer [deftest is testing]]
            [conduit.interceptor :as int]))

;;; -----------------------------------------------------------------------------
;;; Interceptor Creation Tests

(deftest test-interceptor-from-map
  (testing "Create interceptor from map"
    (let [enter-fn (fn [ctx] ctx)
          leave-fn (fn [ctx] ctx)
          error-fn (fn [ctx _err] ctx)
          i (int/interceptor {:name :test
                              :enter enter-fn
                              :leave leave-fn
                              :error error-fn})]
      (is (= :test (:name i)))
      (is (= enter-fn (:enter i)))
      (is (= leave-fn (:leave i)))
      (is (= error-fn (:error i))))))

(deftest test-interceptor-from-function
  (testing "Create interceptor from function"
    (let [f (fn [ctx] (assoc ctx :called true))
          i (int/interceptor f)]
      (is (fn? (:enter i)))
      (is (nil? (:leave i)))
      (is (nil? (:error i)))
      (let [result ((:enter i) {})]
        (is (:called result))))))

(deftest test-interceptor-from-kwargs
  (testing "Create interceptor from keyword arguments"
    (let [enter-fn (fn [ctx] ctx)
          i (int/interceptor :name :kwarg-test :enter enter-fn)]
      (is (= :kwarg-test (:name i)))
      (is (= enter-fn (:enter i))))))

(deftest test-interceptor-validation
  (testing "Interceptor must have at least one phase"
    (is (thrown? AssertionError
                 (int/interceptor {:name :invalid})))))

(deftest test-interceptor-predicate
  (testing "interceptor? predicate"
    (is (int/interceptor? (int/interceptor {:enter (fn [ctx] ctx)})))
    (is (int/interceptor? (int/interceptor {:leave (fn [ctx] ctx)})))
    (is (int/interceptor? (int/interceptor {:error (fn [ctx _err] ctx)})))
    (is (not (int/interceptor? {})))
    (is (not (int/interceptor? nil)))
    (is (not (int/interceptor? "string")))))

;;; -----------------------------------------------------------------------------
;;; Context Creation Tests

(deftest test-context-creation
  (testing "Create context with required fields"
    (let [ctx (int/context {:model :test-model
                            :messages [{:role :user :content "Hi"}]})]
      (is (= :test-model (:model ctx)))
      (is (= [{:role :user :content "Hi"}] (:messages ctx)))
      (is (= {} (:opts ctx)))
      (is (= [] (:queue ctx)))
      (is (= [] (:stack ctx)))
      (is (nil? (:response ctx)))
      (is (nil? (:error ctx)))
      (is (false? (:terminated? ctx))))))

(deftest test-context-with-opts
  (testing "Create context with options"
    (let [ctx (int/context {:model :test-model
                            :messages []
                            :opts {:temperature 0.7}
                            :queue [(int/interceptor (fn [ctx] ctx))]})]
      (is (= {:temperature 0.7} (:opts ctx)))
      (is (= 1 (count (:queue ctx)))))))

(deftest test-context-validation
  (testing "Context requires model and messages"
    (is (thrown? AssertionError
                 (int/context {:messages []})))
    (is (thrown? AssertionError
                 (int/context {:model :test})))))

(deftest test-context-custom-fields
  (testing "Context can have custom fields"
    (let [ctx (int/context {:model :test
                            :messages []
                            :custom-data "value"
                            :metadata {:key 123}})]
      (is (= "value" (:custom-data ctx)))
      (is (= {:key 123} (:metadata ctx))))))

;;; -----------------------------------------------------------------------------
;;; Execution Control Tests

(deftest test-terminate
  (testing "Terminate marks context"
    (let [ctx (int/context {:model :test :messages []})
          terminated (int/terminate ctx)]
      (is (true? (:terminated? terminated)))
      (is (int/terminated? terminated)))))

(deftest test-enqueue
  (testing "Enqueue single interceptor"
    (let [i (int/interceptor (fn [ctx] ctx))
          ctx (int/context {:model :test :messages []})
          result (int/enqueue ctx i)]
      (is (= 1 (count (:queue result))))
      (is (= i (first (:queue result))))))
  
  (testing "Enqueue multiple interceptors"
    (let [i1 (int/interceptor (fn [ctx] ctx))
          i2 (int/interceptor (fn [ctx] ctx))
          i3 (int/interceptor (fn [ctx] ctx))
          ctx (int/context {:model :test :messages []})
          result (int/enqueue ctx i1 i2 i3)]
      (is (= 3 (count (:queue result))))
      (is (= [i1 i2 i3] (:queue result))))))

(deftest test-enqueue-before
  (testing "Enqueue before prepends to queue"
    (let [i1 (int/interceptor {:name :first :enter (fn [ctx] ctx)})
          i2 (int/interceptor {:name :second :enter (fn [ctx] ctx)})
          i3 (int/interceptor {:name :third :enter (fn [ctx] ctx)})
          ctx (int/context {:model :test :messages [] :queue [i1]})
          result (int/enqueue-before ctx i2 i3)]
      (is (= 3 (count (:queue result))))
      (is (= [i2 i3 i1] (:queue result))))))

(deftest test-error-handling
  (testing "Add error to context"
    (let [ctx (int/context {:model :test :messages []})
          err (ex-info "Test error" {})
          result (int/error ctx err)]
      (is (= err (:error result)))))
  
  (testing "Clear error from context"
    (let [ctx (int/context {:model :test :messages []})
          err (ex-info "Test error" {})
          with-error (int/error ctx err)
          cleared (int/clear-error with-error)]
      (is (some? (:error with-error)))
      (is (nil? (:error cleared))))))

;;; -----------------------------------------------------------------------------
;;; Enter Phase Tests

(deftest test-execute-empty-chain
  (testing "Execute empty interceptor chain"
    (let [ctx (int/context {:model :test :messages []})
          result (int/execute ctx)]
      (is (empty? (:queue result)))
      (is (empty? (:stack result)))
      (is (nil? (:error result))))))

(deftest test-execute-single-enter
  (testing "Execute single interceptor with enter only"
    (let [called (atom false)
          i (int/interceptor {:name :test
                              :enter (fn [ctx]
                                       (reset! called true)
                                       (assoc ctx :entered true))})
          ctx (int/context {:model :test :messages [] :queue [i]})
          result (int/execute ctx)]
      (is @called)
      (is (:entered result))
      (is (empty? (:queue result)))
      (is (= 1 (count (:stack result)))))))

(deftest test-execute-multiple-enter
  (testing "Execute multiple interceptors in order"
    (let [order (atom [])
          i1 (int/interceptor {:name :first
                               :enter (fn [ctx]
                                        (swap! order conj :first)
                                        ctx)})
          i2 (int/interceptor {:name :second
                               :enter (fn [ctx]
                                        (swap! order conj :second)
                                        ctx)})
          i3 (int/interceptor {:name :third
                               :enter (fn [ctx]
                                        (swap! order conj :third)
                                        ctx)})
          ctx (int/context {:model :test :messages [] :queue [i1 i2 i3]})
          result (int/execute ctx)]
      (is (= [:first :second :third] @order))
      (is (empty? (:queue result)))
      (is (= 3 (count (:stack result)))))))

(deftest test-execute-enter-transforms-context
  (testing "Interceptors can transform context"
    (let [i1 (int/interceptor {:name :add-field
                               :enter (fn [ctx]
                                        (assoc ctx :field1 "value1"))})
          i2 (int/interceptor {:name :use-field
                               :enter (fn [ctx]
                                        (assoc ctx :field2 (str (:field1 ctx) "-modified")))})
          ctx (int/context {:model :test :messages [] :queue [i1 i2]})
          result (int/execute ctx)]
      (is (= "value1" (:field1 result)))
      (is (= "value1-modified" (:field2 result))))))

(deftest test-execute-with-termination
  (testing "Early termination stops processing"
    (let [i1-called (atom false)
          i2-called (atom false)
          i3-called (atom false)
          i1 (int/interceptor {:name :first
                               :enter (fn [ctx]
                                        (reset! i1-called true)
                                        ctx)})
          i2 (int/interceptor {:name :terminate
                               :enter (fn [ctx]
                                        (reset! i2-called true)
                                        (int/terminate ctx))})
          i3 (int/interceptor {:name :third
                               :enter (fn [ctx]
                                        (reset! i3-called true)
                                        ctx)})
          ctx (int/context {:model :test :messages [] :queue [i1 i2 i3]})
          result (int/execute ctx)]
      (is @i1-called)
      (is @i2-called)
      (is (not @i3-called))
      (is (true? (:terminated? result)))
      (is (= 1 (count (:queue result)))) ; i3 still in queue
      (is (= 2 (count (:stack result))))))) ; i1 and i2 in stack

(deftest test-execute-with-error
  (testing "Error stops enter phase"
    (let [i1-called (atom false)
          i2-called (atom false)
          i3-called (atom false)
          i1 (int/interceptor {:name :first
                               :enter (fn [ctx]
                                        (reset! i1-called true)
                                        ctx)})
          i2 (int/interceptor {:name :error
                               :enter (fn [_ctx]
                                        (reset! i2-called true)
                                        (throw (ex-info "Test error" {})))})
          i3 (int/interceptor {:name :third
                               :enter (fn [ctx]
                                        (reset! i3-called true)
                                        ctx)})
          ctx (int/context {:model :test :messages [] :queue [i1 i2 i3]})
          result (int/execute ctx)]
      (is @i1-called)
      (is @i2-called)
      (is (not @i3-called))
      (is (some? (:error result)))
      (is (= "Test error" (ex-message (:error result)))))))

;;; -----------------------------------------------------------------------------
;;; Leave Phase Tests

(deftest test-execute-leave-empty
  (testing "Execute leave with empty stack"
    (let [ctx (int/context {:model :test :messages []})]
      (int/execute-leave ctx)
      (is (empty? (:stack ctx))))))

(deftest test-execute-leave-single
  (testing "Execute single interceptor leave"
    (let [called (atom false)
          i (int/interceptor {:name :test
                              :leave (fn [ctx]
                                       (reset! called true)
                                       (assoc ctx :left true))})
          ctx (int/context {:model :test 
                            :messages [] 
                            :stack [i]
                            :response {:content "test"}})
          result (int/execute-leave ctx)]
      (is @called)
      (is (:left result))
      (is (empty? (:stack result))))))

(deftest test-execute-leave-reverse-order
  (testing "Execute leave in reverse order"
    (let [order (atom [])
          i1 (int/interceptor {:name :first
                               :leave (fn [ctx]
                                        (swap! order conj :first)
                                        ctx)})
          i2 (int/interceptor {:name :second
                               :leave (fn [ctx]
                                        (swap! order conj :second)
                                        ctx)})
          i3 (int/interceptor {:name :third
                               :leave (fn [ctx]
                                        (swap! order conj :third)
                                        ctx)})
          ctx (int/context {:model :test 
                            :messages []
                            :stack [i1 i2 i3]}) ; Stack from enter phase
          result (int/execute-leave ctx)]
      ;; Stack is LIFO, so reverse order: third, second, first
      (is (= [:third :second :first] @order))
      (is (empty? (:stack result))))))

(deftest test-execute-leave-transforms-response
  (testing "Interceptors can transform response"
    (let [i1 (int/interceptor {:name :add-metadata
                               :leave (fn [ctx]
                                        (update-in ctx [:response :metadata] 
                                                   (fnil merge {}) {:processed true}))})
          i2 (int/interceptor {:name :log
                               :leave (fn [ctx]
                                        (assoc-in ctx [:response :logged] true))})
          ctx (int/context {:model :test
                            :messages []
                            :stack [i1 i2]
                            :response {:content "test"}})
          result (int/execute-leave ctx)]
      (is (get-in result [:response :metadata :processed]))
      (is (get-in result [:response :logged])))))

(deftest test-execute-leave-with-error
  (testing "Error in leave phase is caught"
    (let [i1 (int/interceptor {:name :safe
                               :leave (fn [ctx] ctx)})
          i2 (int/interceptor {:name :error
                               :leave (fn [_ctx]
                                        (throw (ex-info "Leave error" {})))})
          ctx (int/context {:model :test
                            :messages []
                            :stack [i1 i2]})
          result (int/execute-leave ctx)]
      (is (some? (:error result)))
      (is (= "Leave error" (ex-message (:error result)))))))

;;; -----------------------------------------------------------------------------
;;; Error Phase Tests

(deftest test-error-handler-basic
  (testing "Error handler can recover from error"
    (let [recovered (atom false)
          i1 (int/interceptor {:name :fail
                               :enter (fn [_ctx]
                                        (throw (ex-info "Test error" {})))})
          i2 (int/interceptor {:name :recover
                               :error (fn [ctx _err]
                                        (reset! recovered true)
                                        (-> ctx
                                            (int/clear-error)
                                            (assoc :recovered true)))})
          ctx (int/context {:model :test :messages [] :queue [i2 i1]})
          result (int/execute ctx)]
      (is @recovered)
      (is (nil? (:error result)))
      (is (:recovered result)))))

(deftest test-error-handler-chain
  (testing "Error handlers execute in reverse order"
    (let [order (atom [])
          i1 (int/interceptor {:name :first
                               :error (fn [ctx _err]
                                        (swap! order conj :first)
                                        ctx)})
          i2 (int/interceptor {:name :second
                               :error (fn [ctx _err]
                                        (swap! order conj :second)
                                        ctx)})
          i3 (int/interceptor {:name :fail
                               :enter (fn [_ctx]
                                        (throw (ex-info "Test" {})))})
          ctx (int/context {:model :test :messages [] :queue [i1 i2 i3]})]
      (int/execute ctx)
      ;; Error handlers execute in reverse: second, first
      (is (= [:second :first] @order)))))

(deftest test-error-handler-stops-on-clear
  (testing "Clearing error stops error handler chain"
    (let [i1-called (atom false)
          i2-called (atom false)
          i1 (int/interceptor {:name :first
                               :error (fn [ctx _err]
                                        (reset! i1-called true)
                                        ctx)})
          i2 (int/interceptor {:name :second
                               :error (fn [ctx _err]
                                        (reset! i2-called true)
                                        (int/clear-error ctx))})
          i3 (int/interceptor {:name :fail
                               :enter (fn [_ctx]
                                        (throw (ex-info "Test" {})))})
          ctx (int/context {:model :test :messages [] :queue [i1 i2 i3]})
          result (int/execute ctx)]
      (is @i2-called)
      (is (not @i1-called)) ; Stopped after i2 cleared error
      (is (nil? (:error result))))))

(deftest test-error-in-error-handler
  (testing "Error in error handler replaces original error"
    (let [i1 (int/interceptor {:name :bad-handler
                               :error (fn [_ctx err]
                                        (throw (ex-info "Handler error" 
                                                        {:original-error err})))})
          i2 (int/interceptor {:name :fail
                               :enter (fn [_ctx]
                                        (throw (ex-info "Original error" {})))})
          ctx (int/context {:model :test :messages [] :queue [i1 i2]})
          result (int/execute ctx)]
      (is (some? (:error result)))
      (is (= "Handler error" (ex-message (:error result)))))))

;;; -----------------------------------------------------------------------------
;;; Full Chain Tests

(deftest test-execute-all
  (testing "Execute complete chain with enter and leave"
    (let [events (atom [])
          i1 (int/interceptor {:name :first
                               :enter (fn [ctx]
                                        (swap! events conj :first-enter)
                                        ctx)
                               :leave (fn [ctx]
                                        (swap! events conj :first-leave)
                                        ctx)})
          i2 (int/interceptor {:name :second
                               :enter (fn [ctx]
                                        (swap! events conj :second-enter)
                                        ctx)
                               :leave (fn [ctx]
                                        (swap! events conj :second-leave)
                                        ctx)})
          ctx (int/context {:model :test :messages [] :queue [i1 i2]})
          result (int/execute-all ctx)]
      (is (= [:first-enter :second-enter :second-leave :first-leave] @events))
      (is (empty? (:queue result)))
      (is (empty? (:stack result))))))

(deftest test-interceptor-without-phases
  (testing "Interceptor without enter/leave passes through"
    (let [i1 (int/interceptor {:name :enter-only
                               :enter (fn [ctx] (assoc ctx :entered true))})
          i2 (int/interceptor {:name :no-phases
                               ;; Only has name, no functions
                               :enter identity}) ; Must have at least one
          i3 (int/interceptor {:name :leave-only
                               :leave (fn [ctx] (assoc ctx :left true))})
          ctx (int/context {:model :test :messages [] :queue [i1 i2 i3]})
          result (int/execute-all ctx)]
      (is (:entered result))
      (is (:left result)))))

;;; -----------------------------------------------------------------------------
;;; Utility Tests

(deftest test-chain
  (testing "Create chain from interceptor definitions"
    (let [i1 (int/interceptor (fn [ctx] ctx))
          i2 (fn [ctx] ctx)
          i3 (int/interceptor {:name :test :enter (fn [ctx] ctx)})
          chain (int/chain i1 i2 i3)]
      (is (vector? chain))
      (is (= 3 (count chain)))
      (is (every? int/interceptor? chain))))
  
  (testing "Chain flattens nested collections"
    (let [i1 (int/interceptor (fn [ctx] ctx))
          i2 (int/interceptor (fn [ctx] ctx))
          i3 (int/interceptor (fn [ctx] ctx))
          chain (int/chain i1 [i2 i3])]
      (is (= 3 (count chain)))))
  
  (testing "Chain handles nils"
    (let [i1 (int/interceptor (fn [ctx] ctx))
          chain (int/chain i1 nil (fn [ctx] ctx))]
      (is (= 2 (count chain))))))

(deftest test-describe-interceptor
  (testing "Describe interceptor with all phases"
    (let [i (int/interceptor {:name :full
                              :enter (fn [ctx] ctx)
                              :leave (fn [ctx] ctx)
                              :error (fn [ctx _err] ctx)})
          desc (int/describe-interceptor i)]
      (is (= :full (:name desc)))
      (is (= [:enter :leave :error] (:phases desc)))))
  
  (testing "Describe interceptor with some phases"
    (let [i (int/interceptor {:name :partial
                              :enter (fn [ctx] ctx)})
          desc (int/describe-interceptor i)]
      (is (= [:enter] (:phases desc)))))
  
  (testing "Describe anonymous interceptor"
    (let [i (int/interceptor (fn [ctx] ctx))
          desc (int/describe-interceptor i)]
      (is (= "anonymous" (:name desc))))))

(deftest test-describe-context
  (testing "Describe context state"
    (let [ctx (int/context {:model :test
                            :messages [{:role :user :content "Hi"}]
                            :queue [(int/interceptor (fn [ctx] ctx))]})
          desc (int/describe-context ctx)]
      (is (= 1 (:queue-size desc)))
      (is (= 0 (:stack-size desc)))
      (is (false? (:has-error? desc)))
      (is (false? (:terminated? desc)))
      (is (false? (:has-response? desc)))
      (is (= 1 (:message-count desc))))))

;;; -----------------------------------------------------------------------------
;;; Integration Scenarios

(deftest test-cache-scenario
  (testing "Cache interceptor can short-circuit"
    (let [cache (atom {})
          cache-int (int/interceptor
                     {:name :cache
                      :enter (fn [ctx]
                               (if-let [cached (get @cache (:messages ctx))]
                                 (-> ctx
                                     (assoc :response cached)
                                     int/terminate)
                                 ctx))
                      :leave (fn [ctx]
                               (when-not (:cached? ctx)
                                 (swap! cache assoc (:messages ctx) (:response ctx)))
                               ctx)})
          
          ;; First call - cache miss
          ctx1 (int/context {:model :test
                             :messages [{:role :user :content "Hi"}]
                             :queue [cache-int]})
          result1 (int/execute ctx1)]
      
      (is (nil? (:response result1))) ; No cache hit
      (is (not (int/terminated? result1)))
      
      ;; Simulate model call and leave phase
      (let [with-response (assoc result1 :response {:content "Hello"})]
        (int/execute-leave with-response)
        (is (= {:content "Hello"} (:response with-response))))
      
      ;; Second call - cache hit
      (let [ctx2 (int/context {:model :test
                               :messages [{:role :user :content "Hi"}]
                               :queue [cache-int]})
            result2 (int/execute ctx2)]
        (is (some? (:response result2))) ; Cache hit
        (is (int/terminated? result2))))))

(deftest test-logging-scenario
  (testing "Logging interceptor tracks enter and leave"
    (let [events (atom [])
          logging-int (int/interceptor
                       {:name :logging
                        :enter (fn [ctx]
                                 (swap! events conj {:phase :enter
                                                     :messages (:messages ctx)})
                                 ctx)
                        :leave (fn [ctx]
                                 (swap! events conj {:phase :leave
                                                     :response (:response ctx)})
                                 ctx)})
          ctx (int/context {:model :test
                            :messages [{:role :user :content "Hi"}]
                            :queue [logging-int]})
          after-enter (int/execute ctx)
          with-response (assoc after-enter :response {:content "Hello"})]
      (int/execute-leave with-response)
      
      (is (= 2 (count @events)))
      (is (= :enter (:phase (first @events))))
      (is (= :leave (:phase (second @events))))
      (is (= {:content "Hello"} (:response (second @events)))))))

(deftest test-retry-scenario
  (testing "Retry interceptor handles errors"
    (let [attempts (atom 0)
          retry-int (int/interceptor
                     {:name :retry
                      :error (fn [ctx _error]
                               (if (< (:retry-count ctx 0) 3)
                                 (-> ctx
                                     (update :retry-count (fnil inc 0))
                                     int/clear-error)
                                 ctx))})
          fail-int (int/interceptor
                    {:name :fail
                     :enter (fn [ctx]
                              (swap! attempts inc)
                              (if (< @attempts 3)
                                (throw (ex-info "Retry me" {}))
                                ctx))})
          
          ;; This is simplified - real retry would need to re-execute
          ctx (int/context {:model :test
                            :messages []
                            :queue [retry-int fail-int]})
          result (int/execute ctx)]
      
      ;; First execution fails, retry interceptor handles error
      (is (= 1 @attempts))
      (is (nil? (:error result)))
      (is (= 1 (:retry-count result))))))

(deftest test-context-assertions
  (testing "Interceptor must return map"
    (let [bad-int (int/interceptor {:name :bad
                                    :enter (fn [_ctx] "not a map")})
          ctx (int/context {:model :test :messages [] :queue [bad-int]})]
      (is (thrown? AssertionError (int/execute ctx))))))

