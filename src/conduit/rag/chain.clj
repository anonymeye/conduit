(ns conduit.rag.chain
  "RAG (Retrieval-Augmented Generation) chain implementation."
  (:require [conduit.core :as c]
            [conduit.rag.retriever :as retriever]
            [clojure.string :as str]))

(def default-template
  "Default prompt template for RAG.
  
  Placeholders:
    {context}  - Retrieved document context
    {question} - User question"
  "Use the following context to answer the question. If you cannot answer based on the context, say so.

Context:
{context}

Question: {question}

Answer:")

(defn rag-chain
  "Create a RAG chain function.
  
  Arguments:
    opts - Map with:
           :model     - ChatModel instance
           :retriever - Retriever instance
           :template  - Prompt template string (optional, uses default-template)
           :k         - Number of documents to retrieve (default: 5)
           :format-context - Optional function to format context
                            (fn [documents] string)
  
  Returns:
    Function that takes a question string and returns:
    {:answer string
     :sources vector of documents
     :usage usage map}"
  [{:keys [model retriever template k format-context]
    :or {template default-template
         k 5}}]
  (when-not (satisfies? c/ChatModel model)
    (throw (ex-info "Model must implement ChatModel protocol"
                    {:type :validation-error
                     :model model})))
  (when-not (satisfies? retriever/Retriever retriever)
    (throw (ex-info "Retriever must implement Retriever protocol"
                    {:type :validation-error
                     :retriever retriever})))
  (fn [question]
    (let [results (retriever/retrieve retriever question {:k k})
          context (if format-context
                    (format-context results)
                    (->> results
                         (map #(get-in % [:document :content]))
                         (str/join "\n\n---\n\n")))
          prompt (-> template
                     (str/replace "{context}" context)
                     (str/replace "{question}" question))
          response (c/chat model [{:role :user :content prompt}])]
      {:answer (c/extract-content response)
       :sources (mapv :document results)
       :usage (:usage response)
       :scores (mapv :score results)})))

