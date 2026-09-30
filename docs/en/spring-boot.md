# Spring Boot

**English** | [中文](../zh-CN/spring-boot.md)

The `synapse4j-spring-boot-starter` wires a complete stack into a Boot application — the Jackson
codec, a transport, and a chat client — with everything bound from `synapse4j.*` properties.

## Add the dependency

```xml
<dependency>
  <groupId>io.github.synapse4j</groupId>
  <artifactId>synapse4j-spring-boot-starter</artifactId>
  <version>0.0.1</version>
</dependency>
```

## What it wires

Three beans, each backing off if you declare your own:

- **`JsonCodec`** — a `JacksonJsonCodec`. It uses Boot's auto-configured `JsonMapper` when one
  exists, so `spring.jackson.*` and every `JsonMapperBuilderCustomizer` apply to the schemas sent
  to the model and to the JSON a model sends back.
- **`HttpClient`** — the transport `synapse4j.http-client` names.
- **`ChatClient`** — the protocol `synapse4j.chat-client` names, wrapped in `ToolCallingChatClient`
  unless `synapse4j.auto-tool-calling` is off.

## Configuration

```yaml
synapse4j:
  chat-client: completions        # completions (default) | responses | anthropic
  http-client: restclient         # restclient (default) | apache
  auto-tool-calling: true
  openai:
    api-key: ${OPENAI_API_KEY}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
  chat-options:
    model: gpt-4o-mini
    temperature: 0.2
```

| Group | What it binds |
|---|---|
| `synapse4j.openai.*` | `OpenAiConfig`: `base-url`, `api-key`, `organization`, `project`, `max-tokens-field`, `reasoning-field`, `store-responses` |
| `synapse4j.anthropic.*` | `AnthropicConfig`: `base-url`, `api-key`, `anthropic-version` |
| `synapse4j.chat-options.*` | the default `ChatOptions`: `model`, `temperature`, `max-output-tokens`, `top-p`, `reasoning-effort`, `tool-choice`, `tool-choice-name`, `response-format.*`, `headers.*`, `extras.*` |
| `synapse4j.http-options.*` | `HttpOptions`: `body-write-mode`, `response-timeout`, `max-frame-bytes` |

`chat-options.extras` binds raw keys: a key is the provider's own wire name, and a dotted key
addresses a nested member. A non-string value needs YAML — a `.properties` file yields a string for
every value.

Every `synapse4j.*` key has configuration metadata, so your IDE completes them.

## Using it

```java
@Service
class Assistant {

    private final ChatClient client;

    Assistant(ChatClient client) {
        this.client = client;
    }

    ChatResponse ask(String question) {
        return client.chat(new ChatRequest().addUserMessage(question));
    }
}
```

## Customizing the client

Two kinds of bean shape the auto-configured client:

- **`ChatCustomizer`** beans join its per-call hooks, so they run on every call.
- **`ChatClientCustomizer`** beans run after the `synapse4j.chat-options.*` defaults and have the
  last word: they can replace the default options, register tools or tool providers, or add a
  `ChatCustomizer`.

```java
@Bean
ChatClientCustomizer tenantHeader(String tenant) {
    return client -> client.addChatCustomizer(new ChatCustomizer() {
        @Override
        public void customizeRequest(ChatClient c, ChatRequest request) {
            request.getOptions().getHeaders().put("X-Tenant", tenant);
        }
    });
}
```

Both kinds are applied in `@Order` order.

## Declaring your own bean

Every bean the starter defines backs off when you declare one of the same type. Declare your own
`ChatClient` bean to wire the client by hand; the starter's default then steps aside entirely. The
same holds for `JsonCodec` and `HttpClient`.

## Transports

The default transport is Spring's `RestClient`, built on Boot's auto-configured `RestClient.Builder`
when one is published — so interceptors, observations, SSL bundles and `spring.http.client.*`
settings configured for the rest of the application apply to LLM calls too.
`synapse4j.http-options.response-timeout` binds but has no effect on this transport, because
`RestClient` has no per-request timeout; use `spring.http.client.read-timeout` instead.

Selecting `apache` uses Apache HttpClient 5, which the starter does not put on your classpath.
Declare `httpclient5` yourself; without it, only that one bean fails, at startup.
