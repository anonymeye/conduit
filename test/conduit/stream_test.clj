(ns conduit.stream-test
  "Tests for conduit.stream namespace."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a :refer [<!! >!! timeout]]
            [conduit.stream :as stream]))

;; Helper functions

(defn- make-test-channel
  "Create a channel with test events."
  [events]
  (let [ch (a/chan 100)]
    (a/go
      (doseq [event events]
        (a/>! ch event))
      (a/close! ch))
    ch))

(defn- drain-channel
  "Drain all events from a channel into a vector."
  [ch]
  (loop [events []]
    (if-let [event (<!! ch)]
      (recur (conj events event))
      events)))

;; stream->response tests

(deftest test-stream->response-text-only
  (testing "Collecting text-only stream"
    (let [events [{:type :message-start}
                  {:type :content-delta :delta {:text "Hello"}}
                  {:type :content-delta :delta {:text " "}}
                  {:type :content-delta :delta {:text "world"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 10 :output-tokens 20}}]
          ch (make-test-channel events)
          response-ch (stream/stream->response ch)
          response (<!! response-ch)]
      (is (= :assistant (:role response)))
      (is (= "Hello world" (:content response)))
      (is (= :end-turn (:stop-reason response)))
      (is (= {:input-tokens 10 :output-tokens 20} (:usage response)))
      (is (nil? (:tool-calls response))))))

(deftest test-stream->response-with-tool-calls
  (testing "Collecting stream with tool calls"
    (let [events [{:type :message-start}
                  {:type :tool-use-start
                   :tool-call {:id "call_1" :name "get_weather"}}
                  {:type :tool-use-delta
                   :delta {:partial-json "{\"location\":"}}
                  {:type :tool-use-delta
                   :delta {:partial-json "\"NYC\"}"}}
                  {:type :tool-use-stop}
                  {:type :message-end :stop-reason :tool-use
                   :usage {:input-tokens 15 :output-tokens 25}}]
          ch (make-test-channel events)
          response-ch (stream/stream->response ch)
          response (<!! response-ch)]
      (is (= :assistant (:role response)))
      (is (= :tool-use (:stop-reason response)))
      (is (= 1 (count (:tool-calls response))))
      (let [tc (first (:tool-calls response))]
        (is (= "call_1" (:id tc)))
        (is (= :function (:type tc)))
        (is (= "get_weather" (get-in tc [:function :name])))
        (is (= {:location "NYC"} (get-in tc [:function :arguments])))))))

(deftest test-stream->response-error
  (testing "Handling error in stream"
    (let [events [{:type :message-start}
                  {:type :content-delta :delta {:text "Hello"}}
                  {:type :error :error {:type :network :message "Connection lost"}}]
          ch (make-test-channel events)
          response-ch (stream/stream->response ch)
          response (<!! response-ch)]
      (is (map? (:error response)))
      (is (= :network (get-in response [:error :type]))))))

;; stream-with-callbacks tests

(deftest test-stream-with-callbacks
  (testing "Callbacks are invoked correctly"
    (let [events [{:type :message-start :message {:id "msg_1"}}
                  {:type :content-delta :delta {:text "Hello"}}
                  {:type :content-delta :delta {:text " world"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 10 :output-tokens 20}}]
          ch (make-test-channel events)
          start-called (atom false)
          deltas (atom [])
          complete-called (atom false)
          response-ch (stream/stream-with-callbacks ch
                        {:on-start (fn [_] (reset! start-called true))
                         :on-delta (fn [text] (swap! deltas conj text))
                         :on-complete (fn [_] (reset! complete-called true))})]
      (<!! response-ch)
      (is @start-called)
      (is (= ["Hello" " world"] @deltas))
      (is @complete-called))))

(deftest test-stream-with-callbacks-tool
  (testing "Tool callback is invoked"
    (let [events [{:type :message-start}
                  {:type :tool-use-start
                   :tool-call {:id "call_1" :name "test_tool"}}
                  {:type :tool-use-delta
                   :delta {:partial-json "{\"arg\":\"value\"}"}}
                  {:type :tool-use-stop}
                  {:type :message-end :stop-reason :tool-use
                   :usage {:input-tokens 10 :output-tokens 20}}]
          ch (make-test-channel events)
          tool-calls (atom [])
          response-ch (stream/stream-with-callbacks ch
                        {:on-tool (fn [tc] (swap! tool-calls conj tc))})]
      (<!! response-ch)
      (is (= 1 (count @tool-calls)))
      (is (= "call_1" (:id (first @tool-calls)))))))

(deftest test-stream-with-callbacks-error
  (testing "Error callback is invoked"
    (let [events [{:type :message-start}
                  {:type :error :error {:type :timeout :message "Timeout"}}]
          ch (make-test-channel events)
          error-called (atom nil)
          response-ch (stream/stream-with-callbacks ch
                        {:on-error (fn [err] (reset! error-called err))})]
      (<!! response-ch)
      (is @error-called)
      (is (= :timeout (:type @error-called))))))

;; print-stream tests

(deftest test-print-stream
  (testing "Print stream returns final response"
    (let [events [{:type :message-start}
                  {:type :content-delta :delta {:text "Test"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 5 :output-tokens 10}}]
          ch (make-test-channel events)
          response-ch (stream/print-stream ch)
          response (<!! response-ch)]
      (is (= :assistant (:role response)))
      (is (= "Test" (:content response))))))

;; with-stream-timeout tests

(deftest test-with-stream-timeout-no-timeout
  (testing "Stream completes before timeout"
    (let [events [{:type :content-delta :delta {:text "Fast"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 5 :output-tokens 10}}]
          ch (make-test-channel events)
          timeout-ch (stream/with-stream-timeout ch 1000)
          collected (drain-channel timeout-ch)]
      (is (= 2 (count collected)))
      (is (= :content-delta (:type (first collected))))
      (is (= :message-end (:type (second collected)))))))

(deftest test-with-stream-timeout-times-out
  (testing "Stream times out"
    (let [ch (a/chan 100)
          timeout-ch (stream/with-stream-timeout ch 100)]
      ;; Don't send any events, let it timeout
      (let [events (drain-channel timeout-ch)]
        (is (= 1 (count events)))
        (is (= :error (:type (first events))))
        (is (= :timeout (get-in (first events) [:error :type])))))))

;; SSE parsing tests

(deftest test-parse-sse-line
  (testing "Parse data line"
    (is (= {:data "hello"} (stream/parse-sse-line "data: hello"))))
  
  (testing "Parse event line"
    (is (= {:event "message"} (stream/parse-sse-line "event: message"))))
  
  (testing "Parse id line"
    (is (= {:id "123"} (stream/parse-sse-line "id: 123"))))
  
  (testing "Ignore comment"
    (is (nil? (stream/parse-sse-line ": this is a comment"))))
  
  (testing "Ignore blank line"
    (is (nil? (stream/parse-sse-line "")))))

(deftest test-sse-lines->events
  (testing "Convert SSE lines to events"
    (let [lines ["data: first line"
                 "event: test"
                 ""
                 "data: second line"
                 ""]
          lines-ch (make-test-channel lines)
          events-ch (stream/sse-lines->events lines-ch)
          events (drain-channel events-ch)]
      (is (= 2 (count events)))
      (is (= {:data "first line" :event "test"} (first events)))
      (is (= {:data "second line"} (second events))))))

;; buffered-stream tests

(deftest test-buffered-stream
  (testing "Buffered stream passes through events"
    (let [events [{:type :content-delta :delta {:text "Test"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 5 :output-tokens 10}}]
          ch (make-test-channel events)
          buffered (stream/buffered-stream ch :buffer-size 10)
          collected (drain-channel buffered)]
      (is (= 2 (count collected)))
      (is (= :content-delta (:type (first collected)))))))

;; filter-stream tests

(deftest test-filter-stream
  (testing "Filter stream by predicate"
    (let [events [{:type :message-start}
                  {:type :content-delta :delta {:text "Hello"}}
                  {:type :content-delta :delta {:text " world"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 5 :output-tokens 10}}]
          ch (make-test-channel events)
          filtered (stream/filter-stream ch #(= :content-delta (:type %)))
          collected (drain-channel filtered)]
      (is (= 2 (count collected)))
      (is (every? #(= :content-delta (:type %)) collected)))))

;; map-stream tests

(deftest test-map-stream
  (testing "Transform stream events"
    (let [events [{:type :content-delta :delta {:text "hello"}}
                  {:type :content-delta :delta {:text "world"}}]
          ch (make-test-channel events)
          mapped (stream/map-stream ch 
                   #(update-in % [:delta :text] clojure.string/upper-case))
          collected (drain-channel mapped)]
      (is (= 2 (count collected)))
      (is (= "HELLO" (get-in (first collected) [:delta :text])))
      (is (= "WORLD" (get-in (second collected) [:delta :text]))))))

;; merge-streams tests

(deftest test-merge-streams
  (testing "Merge multiple streams"
    (let [ch1 (make-test-channel [{:type :content-delta :delta {:text "A"}}])
          ch2 (make-test-channel [{:type :content-delta :delta {:text "B"}}])
          merged (stream/merge-streams [ch1 ch2])
          collected (drain-channel merged)]
      (is (= 2 (count collected)))
      (is (some #(= "A" (get-in % [:delta :text])) collected))
      (is (some #(= "B" (get-in % [:delta :text])) collected)))))

;; tap-stream tests

(deftest test-tap-stream
  (testing "Tap stream for multiple consumers"
    (let [events [{:type :content-delta :delta {:text "Test"}}
                  {:type :message-end :stop-reason :end-turn
                   :usage {:input-tokens 5 :output-tokens 10}}]
          ch (make-test-channel events)
          {:keys [tap]} (stream/tap-stream ch)
          tap1 (tap)
          tap2 (tap)
          collected1 (drain-channel tap1)
          collected2 (drain-channel tap2)]
      (is (= 2 (count collected1)))
      (is (= 2 (count collected2)))
      (is (= collected1 collected2)))))

