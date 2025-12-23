(ns conduit.error-test
  (:require [clojure.test :refer :all]
            [conduit.error :as error]))

(deftest test-error-creation
  (testing "basic error"
    (let [e (error/error :validation "Invalid data" {:field :name})]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= "Invalid data" (.getMessage e)))
      (let [data (ex-data e)]
        (is (= :validation (:type data)))
        (is (= "Invalid data" (:message data)))
        (is (= :name (:field data)))
        (is (number? (:timestamp data))))))
  
  (testing "rate-limit-error"
    (let [e (error/rate-limit-error "Too many requests" 
                                    {:retry-after 60 :provider :grok})]
      (is (= :rate-limit (error/error-type e)))
      (is (= 60 (:retry-after (ex-data e))))
      (is (= :grok (:provider (ex-data e))))))
  
  (testing "authentication-error"
    (let [e (error/authentication-error :openai)]
      (is (= :authentication (error/error-type e)))
      (is (= :openai (:provider (ex-data e))))))
  
  (testing "server-error"
    (let [e (error/server-error 500 "Internal error" :anthropic)]
      (is (= :server-error (error/error-type e)))
      (is (= 500 (:status (ex-data e))))
      (is (= :anthropic (:provider (ex-data e)))))))

(deftest test-error-predicates
  (testing "error-type"
    (let [e (error/error :rate-limit "test" {})]
      (is (= :rate-limit (error/error-type e)))))
  
  (testing "conduit-error?"
    (is (true? (error/conduit-error? (error/error :validation "test" {}))))
    (is (false? (error/conduit-error? (Exception. "test")))))
  
  (testing "rate-limited?"
    (is (true? (error/rate-limited? (error/rate-limit-error "test" {}))))
    (is (false? (error/rate-limited? (error/authentication-error :grok)))))
  
  (testing "retryable?"
    (is (true? (error/retryable? (error/rate-limit-error "test" {}))))
    (is (true? (error/retryable? (error/server-error 500 "err" :grok))))
    (is (false? (error/retryable? (error/authentication-error :grok)))))
  
  (testing "client-error?"
    (is (true? (error/client-error? (error/authentication-error :grok))))
    (is (false? (error/client-error? (error/server-error 500 "err" :grok))))))

(deftest test-status-mapping
  (testing "status->error-type"
    (is (= :invalid-request (error/status->error-type 400)))
    (is (= :authentication (error/status->error-type 401)))
    (is (= :authorization (error/status->error-type 403)))
    (is (= :not-found (error/status->error-type 404)))
    (is (= :rate-limit (error/status->error-type 429)))
    (is (= :server-error (error/status->error-type 500)))
    (is (= :server-error (error/status->error-type 503)))))

(deftest test-try-call
  (testing "successful call"
    (let [result (error/try-call + 1 2)]
      (is (= {:ok 3} result))))
  
  (testing "failed call with conduit error"
    (let [result (error/try-call #(throw (error/error :validation "bad" {})))]
      (is (contains? result :error))
      (is (= :validation (get-in result [:error :type])))))
  
  (testing "failed call with unknown error"
    (let [result (error/try-call #(throw (Exception. "unknown")))]
      (is (contains? result :error))
      (is (= :unknown (get-in result [:error :type]))))))

