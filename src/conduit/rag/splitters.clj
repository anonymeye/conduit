(ns conduit.rag.splitters
  "Text splitting utilities for RAG document processing."
  (:require [clojure.string :as str]))

(defprotocol TextSplitter
  "Protocol for text splitting strategies."
  (split [this text]
    "Split text into chunks.
    
    Arguments:
      this - The splitter instance
      text - String to split
      
    Returns:
      Vector of string chunks"))

(defn- add-overlap
  "Add overlap between chunks."
  [chunks _chunk-size chunk-overlap]
  (if (or (empty? chunks) (zero? chunk-overlap))
    chunks
    (let [overlapped (atom [])]
      (doseq [[idx chunk] (map-indexed vector chunks)]
        (if (zero? idx)
          (swap! overlapped conj chunk)
          (let [prev-chunk (nth chunks (dec idx))
                overlap-start (max 0 (- (count prev-chunk) chunk-overlap))
                overlap-text (subs prev-chunk overlap-start)
                new-chunk (str overlap-text chunk)]
            (swap! overlapped conj new-chunk))))
      @overlapped)))

(defn character-splitter
  "Split by character count with overlap.
  
  Options:
    :chunk-size    - Target chunk size (default: 1000)
    :chunk-overlap - Overlap between chunks (default: 200)
    :separator     - Split on this first (default: \"\\n\\n\")
  
  Returns:
    TextSplitter instance"
  [{:keys [chunk-size chunk-overlap separator]
    :or {chunk-size 1000
         chunk-overlap 200
         separator "\n\n"}}]
  (reify TextSplitter
    (split [_ text]
      (if (<= (count text) chunk-size)
        [text]
        (let [parts (str/split text (re-pattern (java.util.regex.Pattern/quote separator)))
              chunks (atom [])
              current (atom "")]
          (doseq [part parts]
            (let [candidate (if (empty? @current)
                              part
                              (str @current separator part))]
              (if (<= (count candidate) chunk-size)
                (reset! current candidate)
                (do
                  (when (not-empty @current)
                    (swap! chunks conj @current))
                  (if (<= (count part) chunk-size)
                    (reset! current part)
                    (do
                      ;; Split large part into fixed-size chunks
                      (doseq [chunk (partition-all chunk-size part)]
                        (swap! chunks conj (apply str chunk)))
                      (reset! current "")))))))
          (when (not-empty @current)
            (swap! chunks conj @current))
          (add-overlap @chunks chunk-size chunk-overlap))))))

(defn recursive-splitter
  "Recursively split using multiple separators.
  
  Options:
    :chunk-size    - Target chunk size (default: 1000)
    :chunk-overlap - Overlap between chunks (default: 200)
    :separators    - Vector of separators to try in order
                     (default: [\"\\n\\n\" \"\\n\" \". \" \" \"])
  
  Returns:
    TextSplitter instance"
  [{:keys [chunk-size chunk-overlap separators]
    :or {chunk-size 1000
         chunk-overlap 200
         separators ["\n\n" "\n" ". " " "]}}]
  (letfn [(split-text [text seps]
            (cond
              (empty? seps) [text]
              (<= (count text) chunk-size) [text]
              :else
              (let [[sep & rest-seps] seps
                    pattern (re-pattern (java.util.regex.Pattern/quote sep))
                    parts (str/split text pattern)]
                (if (= 1 (count parts))
                  (split-text text rest-seps)
                  (merge-chunks parts sep rest-seps)))))
          
          (merge-chunks [parts sep rest-seps]
            (let [chunks (atom [])
                  current (atom "")]
              (doseq [part parts]
                (let [candidate (if (empty? @current)
                                  part
                                  (str @current sep part))]
                  (if (<= (count candidate) chunk-size)
                    (reset! current candidate)
                    (do
                      (when (not-empty @current)
                        (swap! chunks conj @current))
                      (if (<= (count part) chunk-size)
                        (reset! current part)
                        (let [sub-chunks (split-text part rest-seps)]
                          (doseq [chunk sub-chunks]
                            (if (<= (+ (count @current) (count chunk)) chunk-size)
                              (reset! current (str @current chunk))
                              (do
                                (when (not-empty @current)
                                  (swap! chunks conj @current))
                                (reset! current chunk))))))))))
              (when (not-empty @current)
                (swap! chunks conj @current))
              (add-overlap @chunks chunk-size chunk-overlap)))]
    (reify TextSplitter
      (split [_ text]
        (split-text text separators)))))

(defn sentence-splitter
  "Split by sentences using regex.
  
  Options:
    :chunk-size    - Target chunk size (default: 1000)
    :chunk-overlap - Overlap between chunks (default: 200)
  
  Returns:
    TextSplitter instance"
  [{:keys [chunk-size chunk-overlap]
    :or {chunk-size 1000
         chunk-overlap 200}}]
  (let [sentence-pattern #"[.!?]+\s+"]
    (reify TextSplitter
      (split [_ text]
        (if (<= (count text) chunk-size)
          [text]
          (let [sentences (str/split text sentence-pattern)
                chunks (atom [])
                current (atom "")]
            (doseq [sentence sentences]
              (let [sentence-with-punct (str sentence ". ")
                    candidate (if (empty? @current)
                                sentence-with-punct
                                (str @current sentence-with-punct))]
                (if (<= (count candidate) chunk-size)
                  (reset! current candidate)
                  (do
                    (when (not-empty @current)
                      (swap! chunks conj (str/trim @current)))
                    (if (<= (count sentence-with-punct) chunk-size)
                      (reset! current sentence-with-punct)
                      ;; Sentence too large, split by words
                      (let [words (str/split sentence #"\s+")
                            word-chunk (atom "")]
                        (doseq [word words]
                          (let [candidate-word (if (empty? @word-chunk)
                                                  word
                                                  (str @word-chunk " " word))]
                            (if (<= (count candidate-word) chunk-size)
                              (reset! word-chunk candidate-word)
                              (do
                                (when (not-empty @word-chunk)
                                  (swap! chunks conj @word-chunk))
                                (reset! word-chunk word)))))
                        (reset! current @word-chunk)))))))
            (when (not-empty @current)
              (swap! chunks conj (str/trim @current)))
            (add-overlap @chunks chunk-size chunk-overlap)))))))

(defn markdown-splitter
  "Split markdown by headers.
  
  Options:
    :chunk-size    - Target chunk size (default: 1000)
    :chunk-overlap - Overlap between chunks (default: 200)
    :header-levels  - Vector of header patterns to split on
                      (default: [\"# \" \"## \" \"### \"])
  
  Returns:
    TextSplitter instance"
  [{:keys [chunk-size chunk-overlap header-levels]
    :or {chunk-size 1000
         chunk-overlap 200
         header-levels ["# " "## " "### "]}}]
  (reify TextSplitter
    (split [_ text]
      (let [header-pattern (re-pattern (str "^(" (str/join "|" (map #(java.util.regex.Pattern/quote %) header-levels)) ")"))
            lines (str/split-lines text)
            chunks (atom [])
            current-section (atom [])]
        (doseq [line lines]
          (if (re-find header-pattern line)
            (do
              (when (not-empty @current-section)
                (let [section-text (str/join "\n" @current-section)]
                  (if (<= (count section-text) chunk-size)
                    (swap! chunks conj section-text)
                    ;; Section too large, recursively split
                    (let [recursive (recursive-splitter {:chunk-size chunk-size
                                                         :chunk-overlap chunk-overlap})]
                      (doseq [chunk (split recursive section-text)]
                        (swap! chunks conj chunk)))))
                (reset! current-section []))
              (swap! current-section conj line))
            (swap! current-section conj line)))
        (when (not-empty @current-section)
          (let [section-text (str/join "\n" @current-section)]
            (if (<= (count section-text) chunk-size)
              (swap! chunks conj section-text)
              (let [recursive (recursive-splitter {:chunk-size chunk-size
                                                   :chunk-overlap chunk-overlap})]
                (doseq [chunk (split recursive section-text)]
                  (swap! chunks conj chunk))))))
        (let [result (add-overlap @chunks chunk-size chunk-overlap)]
          (if (empty? result)
            [text]  ; If no chunks created, return original text
            result))))))

