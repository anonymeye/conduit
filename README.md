# Conduit

> A Simple, Functional LangChain for the JVM

[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue.svg)](https://clojure.org/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Clojars](https://img.shields.io/clojars/v/org.clojars.conduit/conduit.svg)](https://clojars.org/org.clojars.conduit/conduit)

**Conduit** is a Clojure library that provides a unified, functional interface to LLM providers. Unlike framework-heavy approaches, Conduit embraces Clojure's philosophy: **data-first, functions over frameworks, explicit over implicit**.

## ✨ Features

- **🔌 Provider Agnostic** - Unified protocol interface for any LLM provider
- **🛠️ Tool Calling** - Built-in support for function calling and tool execution
- **🌊 Streaming** - First-class streaming support with `core.async`
- **🔗 Interceptors** - Composable middleware pattern for cross-cutting concerns
- **📊 RAG Support** - Complete RAG pipeline with embeddings, vector stores, and retrieval
- **🎯 Agents** - Autonomous agent loops with tool execution
- **📝 Structured Output** - Schema-constrained response generation
- **🔄 Workflow Composition** - Pipeline and graph-based workflow orchestration
- **💾 Memory Management** - Conversation memory with windowing and summarization
- **✅ Type Safety** - Malli schema validation throughout

## 🚀 Quick Start

### Installation

Add Conduit to your `deps.edn`:

```clojure
{:deps {org.clojars.conduit/conduit {:mvn/version "X.Y.Z"}}}
```

Or with Leiningen:

```clojure
[org.clojars.conduit/conduit "X.Y.Z"]
```

### Basic Usage

```clojure
(require '[conduit.core :as c]
         '[conduit.providers.grok :as grok])

;; Create a model instance
(def model (grok/model {:model "grok-3"}))

;; Simple chat
(c/chat model [{:role :user :content "Hello!"}])
;; => {:role :assistant :content "..." :usage {...}}

;; With streaming
(let [ch (c/stream model [{:role :user :content "Hello!"}])]
  (go-loop []
    (when-let [event (<! ch)]
      (println (:content event))
      (recur))))
```

## 📚 Documentation

- **[Architecture Overview](docs/01-architecture.md)** - System design and protocols
- **[Schemas](docs/02-schemas.md)** - Data structures and validation
- **[Providers](docs/04-providers.md)** - LLM provider implementations
- **[Tools & Agents](docs/06-tools.md)** - Function calling and agent loops
- **[Streaming](docs/07-streaming.md)** - Streaming responses with core.async
- **[RAG](docs/10-rag.md)** - Retrieval-Augmented Generation
- **[Interceptors](docs/05-middleware.md)** - Middleware and cross-cutting concerns
- **[Workflows](docs/08-graph.md)** - Pipeline and graph orchestration
- **[API Reference](docs/14-api-reference.md)** - Complete API documentation

## 🎯 Key Concepts

### Data-First Design

Everything in Conduit is plain Clojure data—maps, vectors, keywords. No magic classes, no hidden state.

```clojure
;; Messages are maps
{:role :user :content "Hello!"}

;; Responses are maps
{:role :assistant :content "Hi!" :usage {:input-tokens 5 :output-tokens 3}}

;; Tools are maps
{:name "get_weather" 
 :description "Get weather for a location"
 :schema [:map [:location :string]]
 :fn weather-fn}
```

### Functions Over Frameworks

Composition happens with standard Clojure: `comp`, `->`, transducers. No DSLs, no chain classes.

```clojure
;; A pipeline is just composed functions
(def my-pipeline
  (comp extract-answer
        (call-llm model)
        add-system-prompt))
```

### Provider Agnostic

Protocols define the interface. Swap providers without changing application code.

```clojure
;; Same code, different providers
(c/chat claude-model messages)
(c/chat gpt-model messages)
(c/chat grok-model messages)
```

## 💡 Examples

### Tool Calling

```clojure
(require '[conduit.tools :as tools]
         '[conduit.agent :as agent])

(def weather-tool
  (tools/tool
    {:name "get_weather"
     :description "Get current weather for a location"
     :schema [:map [:location :string]]
     :fn (fn [{:keys [location]}]
           {:temperature 72 :condition "sunny"})}))

(def agent (agent/make-agent model [weather-tool]))

(agent "What's the weather in San Francisco?")
```

### RAG Pipeline

```clojure
(require '[conduit.rag.chain :as rag]
         '[conduit.rag.stores.memory :as store])

(def vector-store (store/memory-store))

;; Add documents
(store/add-documents vector-store documents embeddings)

;; Query with RAG
(rag/rag-chain model vector-store embedding-model
               "What is machine learning?"
               {:k 5})
```

### Interceptors

```clojure
(require '[conduit.interceptors :as interceptors])

(def intercepted-chat
  (c/chat-with-interceptors
    model
    messages
    [(interceptors/retry-interceptor {:max-attempts 3})
     (interceptors/logging-interceptor {:level :info})
     (interceptors/cache-interceptor {:ttl-seconds 3600})]))
```

### Workflow Pipelines

```clojure
(require '[conduit.flow :as flow])

(def pipeline
  (flow/pipeline
    [(flow/llm-step :generate model
                    {:prompt-fn (fn [s] [{:role :user :content (:input s)}])
                     :interceptors [retry-int logging-int]})
     (flow/transform-step :extract
                         (fn [s] (assoc s :output (c/extract-content (:response s)))))]))

(pipeline {:input "Hello!"} {})
```

## 🏗️ Architecture

Conduit is built on three core protocols:

- **`ChatModel`** - Chat and streaming interfaces
- **`Embeddable`** - Embedding generation
- **`Wrappable`** - Model wrapping for advanced use cases

All providers implement these protocols, ensuring a consistent API across different LLM services.

## 🔌 Supported Providers

- ✅ **Grok (xAI)** - Full support with streaming
- ✅ **Groq** - Fast inference with streaming
- 🔜 **Anthropic (Claude)** - Coming soon
- 🔜 **OpenAI (GPT)** - Coming soon
- 🔜 **Google (Gemini)** - Coming soon
- 🔜 **Ollama** - Coming soon

## 🛠️ Technology Stack

| Concern | Technology | Rationale |
|---------|-----------|-----------|
| Language | Clojure 1.11+ | Modern Clojure features |
| Async/Streaming | `core.async` | Idiomatic Clojure, battle-tested |
| Schema/Validation | `Malli` | Data-driven, excellent error messages |
| HTTP Client | `clj-http` | Mature, synchronous-first |
| JSON | `Cheshire` | Fast, standard |

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Inspired by LangChain's vision but built with Clojure's functional philosophy
- Built on the shoulders of excellent Clojure libraries: `core.async`, `Malli`, `clj-http`, and `Cheshire`

---

**Made with ❤️ for the Clojure community**
