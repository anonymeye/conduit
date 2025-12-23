(ns conduit.util.json
  "JSON encoding and decoding utilities using Cheshire."
  (:require [cheshire.core :as json]
            [cheshire.generate :as json-gen]))

;; Encoding

(defn encode
  "Encode Clojure data to JSON string.
  
  Arguments:
    data - Clojure data structure
    opts - Optional map with:
           :pretty - Pretty print (default false)
           :key-fn - Function to transform keys (default name for keywords)
  
  Returns:
    JSON string"
  ([data]
   (encode data {}))
  ([data {:keys [pretty key-fn]
          :or {pretty false
               key-fn name}}]
   (if pretty
     (json/generate-string data {:pretty true :key-fn key-fn})
     (json/generate-string data {:key-fn key-fn}))))

(defn encode-pretty
  "Encode Clojure data to pretty-printed JSON string.
  
  Arguments:
    data - Clojure data structure
  
  Returns:
    Pretty-printed JSON string"
  [data]
  (encode data {:pretty true}))

;; Decoding

(defn decode
  "Decode JSON string to Clojure data.
  
  Arguments:
    s    - JSON string
    opts - Optional map with:
           :keywordize - Convert keys to keywords (default true)
  
  Returns:
    Clojure data structure"
  ([s]
   (decode s {:keywordize true}))
  ([s {:keys [keywordize]
       :or {keywordize true}}]
   (json/parse-string s keywordize)))

(defn decode-stream
  "Decode JSON from a stream/reader.
  
  Arguments:
    reader - java.io.Reader
    opts   - Optional map with:
             :keywordize - Convert keys to keywords (default true)
  
  Returns:
    Clojure data structure"
  ([reader]
   (decode-stream reader {:keywordize true}))
  ([reader {:keys [keywordize]
            :or {keywordize true}}]
   (json/parse-stream reader keywordize)))

;; Safe Operations

(defn try-decode
  "Try to decode JSON, returning nil on failure.
  
  Arguments:
    s    - JSON string
    opts - Optional decode options
  
  Returns:
    Clojure data structure or nil if decode fails"
  ([s]
   (try-decode s {:keywordize true}))
  ([s opts]
   (try
     (decode s opts)
     (catch Exception _
       nil))))

(defn try-encode
  "Try to encode data, returning nil on failure.
  
  Arguments:
    data - Clojure data structure
    opts - Optional encode options
  
  Returns:
    JSON string or nil if encode fails"
  ([data]
   (try-encode data {}))
  ([data opts]
   (try
     (encode data opts)
     (catch Exception _
       nil))))

;; Validation

(defn valid-json?
  "Check if string is valid JSON.
  
  Arguments:
    s - String to check
  
  Returns:
    true if s is valid JSON, false otherwise"
  [s]
  (some? (try-decode s)))

;; Streaming Support

(defn parse-json-lines
  "Parse newline-delimited JSON (JSONL/NDJSON).
  
  Arguments:
    s    - String with newline-delimited JSON
    opts - Optional decode options
  
  Returns:
    Lazy sequence of parsed objects"
  ([s]
   (parse-json-lines s {:keywordize true}))
  ([s opts]
   (->> (clojure.string/split-lines s)
        (remove clojure.string/blank?)
        (map #(try-decode % opts))
        (remove nil?))))

;; Content Type Helpers

(defn json-content-type?
  "Check if content-type header indicates JSON.
  
  Arguments:
    content-type - Content-Type header value
  
  Returns:
    true if JSON content type"
  [content-type]
  (when content-type
    (boolean (re-find #"application/json" (str content-type)))))

;; Custom Encoders

(defn add-encoder
  "Add custom JSON encoder for a type.
  
  Arguments:
    type      - Java class
    encode-fn - Function that takes value and json-generator
  
  Example:
    (add-encoder java.time.Instant
                 (fn [inst gen]
                   (.writeString gen (.toString inst))))"
  [type encode-fn]
  (json-gen/add-encoder type encode-fn))

;; Common Transformations

(defn keywordize-keys
  "Recursively transform all map keys to keywords.
  
  Arguments:
    m - Map or nested structure
  
  Returns:
    Structure with keywordized keys"
  [m]
  (cond
    (map? m)
    (into {} (map (fn [[k v]]
                    [(if (string? k) (keyword k) k)
                     (keywordize-keys v)])
                  m))
    
    (sequential? m)
    (mapv keywordize-keys m)
    
    :else m))

(defn stringify-keys
  "Recursively transform all map keys to strings.
  
  Arguments:
    m - Map or nested structure
  
  Returns:
    Structure with string keys"
  [m]
  (cond
    (map? m)
    (into {} (map (fn [[k v]]
                    [(if (keyword? k) (name k) (str k))
                     (stringify-keys v)])
                  m))
    
    (sequential? m)
    (mapv stringify-keys m)
    
    :else m))

;; Merge Utilities

(defn merge-json
  "Merge multiple JSON strings into one object.
  
  Arguments:
    json-strings - Variable number of JSON strings
  
  Returns:
    Merged object as JSON string"
  [& json-strings]
  (->> json-strings
       (map decode)
       (apply merge)
       encode))

;; Pretty Printing

(defn pprint
  "Pretty print JSON to stdout.
  
  Arguments:
    data - Clojure data structure"
  [data]
  (println (encode-pretty data)))

;; Size Helpers

(defn json-size
  "Get size of data when encoded as JSON.
  
  Arguments:
    data - Clojure data structure
  
  Returns:
    Size in bytes"
  [data]
  (count (.getBytes (encode data) "UTF-8")))

