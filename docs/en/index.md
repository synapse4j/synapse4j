# synapse4j

**English** | [中文](../zh-CN/index.md)

A lightweight Java library for calling LLM providers.

You write a call once — the messages, the model, the tools — and it works against whichever
provider you point it at. You choose the JSON library, the HTTP client and the provider. The
library supplies the model in between and nothing else.

```java
JsonCodec codec = new JacksonJsonCodec();
HttpClient http = new JdkHttpClient();

OpenAiConfig config = new OpenAiConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));

ChatClient client = new OpenAiCompletionsChatClient(http, codec, config);

ChatResponse response = client.chat(new ChatRequest()
        .addUserMessage("Why is the sky blue?"));

System.out.println(response.getText());
```

## What makes it different

Most Java LLM libraries bundle a stack: a JSON library, an HTTP client, a way to keep conversation
history, sometimes a whole framework. synapse4j bundles none of them.

- **One request and response model, for every provider.** OpenAI, Anthropic and the rest are
  reached through the same types. Changing provider changes which client you build, not the code
  around it.
- **Your JSON library.** The library never serializes anything itself — it asks the codec you hand
  it. Jackson is one implementation, not a requirement.
- **Your HTTP client.** The JDK client, Spring's `RestClient`, Apache HttpClient 5, or your own.
- **Blocking and streaming are both first-class**, and share the same request.
- **Tool calling is built in.** Declare a tool, pair it with the code behind it, and let the
  library run the model's tool-call rounds.
- **No state.** The library stores nothing between calls; conversation history is yours to keep,
  wherever you keep it.

## Reading order

1. [Getting started](getting-started.md) — a working call in a few minutes, then streaming, tools
   and structured output.
2. [The call model](model.md) — the request, the response, messages, parts and options.
3. [Conversations](conversations.md) — holding history yourself, and continuing across turns.
4. [Tools](tools.md) — declaring tools and running the model's tool calls.
5. [Streaming](streaming.md) — consuming an answer event by event.
6. [Structured output](structured-output.md) — asking for JSON against a schema.
7. [Customizing](customizing.md) — swapping components, hooks, defaults, per-call settings and the
   generated schema.
8. [Spring Boot](spring-boot.md) — the auto-configuring starter.
9. [Writing a provider](writing-a-provider.md) — the shape of a provider module.
10. [Design and trade-offs](design.md) — what the library does and does not do, and why.
11. [Comparison with other Java libraries](comparison.md) — how it differs from LangChain4j,
    Spring AI and the vendor SDKs.

The Javadoc is the reference for the API itself.

## Modules

Pick the modules you need; the BOM aligns their versions. [The README](../../README.md#modules)
lists them.

## Requirements

- Java 21 or newer.
- Maven 3.6.3 or newer if you build the project itself (there is no wrapper; run `mvn` directly).
- Spring Boot 4.1 or newer to use the starter.

## License

Apache License 2.0 — see [LICENSE](../../LICENSE).
