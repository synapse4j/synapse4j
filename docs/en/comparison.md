# Comparison with other Java libraries

**English** | [中文](../zh-CN/comparison.md)

This page is about behavior, not code: what each library does for you, and where the lines between
them are. It covers the three shapes you are most likely choosing between — a toolkit, a framework,
and a vendor SDK — and where synapse4j sits.

## At a glance

| | synapse4j | LangChain4j | Spring AI | Official SDKs |
|---|---|---|---|---|
| Conversation memory | none; you hold it | built in (`ChatMemory`, window, eviction, stores) | built in (`ChatMemory` plus advisors) | none |
| Provider abstraction | one model, provider modules | one model, per-provider modules | `ChatModel` plus `ChatClient` | one provider only |
| Framework coupling | none | none (a toolkit) | Spring, best with Boot | none |
| JSON library | yours, behind an interface | Jackson | Jackson | its own, generated |
| HTTP client | yours, behind an interface | its own clients | Spring's HTTP | its own (OkHttp) |
| API style | blocking; a stream is pulled | blocking plus streaming callbacks | blocking plus streaming (Flux) | blocking plus streaming |
| Declarative interface | no | yes (`AiService`) | no | no |
| Tool calling | `Tool` plus an executor and a loop | `@Tool` annotations | `@Tool` plus `ChatClient` | raw calls |
| Extending the model | open structures, plus a pass-through bag | its own types | its own types | generated types |

## LangChain4j

A toolkit: it ships the pieces for a whole application — chat models, embeddings, RAG, agents,
memory — and stays framework-agnostic.

Its centerpiece is the declarative **`AiService`**: you write a plain interface, annotate it, and
the library generates the implementation.

```java
interface Assistant {
    @SystemMessage("You are a helpful assistant.")
    String chat(@UserMessage String question);
}
```

Conversation memory is a first-class component: a `ChatMemory` keeps history, with window limits,
eviction and pluggable stores for persistence. A default service keeps a window of recent messages
without you asking.

Choose it when you want batteries included and do not mind adopting its types and its SPI.

## Spring AI

Spring-native. A `ChatModel` abstracts a provider; a `ChatClient` is the fluent front door, and
`ChatMemory` plus advisors add memory and other cross-cutting behavior.

Memory is wired through an advisor — `MessageChatMemoryAdvisor` pulls history from a `ChatMemory`
and writes answers back — so it is opt-in per client, and scoping it to the right conversations is
the application's job.

Choose it when your application is already Spring Boot and you want LLM calls to look like the rest
of it.

## Official SDKs

`openai-java` and `anthropic-java` are generated, per-provider clients. They expose one vendor's
full API surface — batch jobs, files, everything — with immutable, builder-based request types.

There is no provider-neutral model and no memory: each SDK speaks exactly one provider, and moving
between them means rewriting the calls.

Choose one when you use a single provider and want direct access to its whole API, especially for
features beyond chat.

## Where synapse4j sits

synapse4j is the smallest of the four. It keeps the neutral model and tool calling, and leaves out
what the others bundle: no conversation memory, no framework, and no fixed JSON library or HTTP
client. You keep history and pick the pieces.

That has costs, stated plainly: you write the loop that stores conversations, the library is
blocking rather than reactive, and it covers fewer providers than the others. It buys you a stack
that is yours — swap the JSON library, the HTTP client or the provider without touching a core
abstraction, and keep the dependency tree small.

A rough guide:

- **One provider, direct API access** — an official SDK.
- **A Spring Boot application** — Spring AI.
- **A toolkit with memory, RAG and agents** — LangChain4j.
- **A small, unopinionated layer you assemble yourself** — synapse4j.

## Sources

- [LangChain4j `AiServices`](https://docs.langchain4j.dev/apidocs/dev/langchain4j/service/AiServices.html)
  and its chat-memory components.
- [Spring AI reference — chat clients and the Anthropic migration](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-migration.html).
- [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) and the OpenAI Java SDK.
