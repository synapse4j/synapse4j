# synapse4j

**English** | [中文](README.zh-CN.md)

A lightweight Java library for calling LLM providers. You write a call once and it works against
whichever provider you point it at; you pick the JSON library, the HTTP client and the provider,
and the library stays out of everything else.

It covers JSON Schema generation and JSON serialization, a provider-neutral chat model with
blocking and streaming, and tool calling end to end. Provider modules ship separately.

## Modules

| Module | What it is |
|---|---|
| `synapse4j-core` | The provider-neutral model, JSON/HTTP interfaces, tool calling |
| `synapse4j-jackson` | `JsonCodec` on Jackson |
| `synapse4j-http-jdk` | `HttpClient` on the JDK's `java.net.http` |
| `synapse4j-http-apache` | `HttpClient` on Apache HttpClient 5 |
| `synapse4j-http-restclient` | `HttpClient` on Spring's `RestClient` |
| `synapse4j-openai` | Chat Completions and Responses protocols |
| `synapse4j-anthropic` | Anthropic Messages protocol |
| `synapse4j-spring-boot-starter` | Auto-configuration for Spring Boot |
| `synapse4j-bom` | Bill of materials for consumers |

## Documentation

- [Getting started](docs/en/getting-started.md) — assemble a client, make a call, stream, run a
  tool, ask for structured output. No framework required.
- [Design and trade-offs](docs/en/design.md) — why the library is shaped this way.
- [Full documentation index](docs/en/index.md)

中文文档见 [docs/zh-CN](docs/zh-CN/index.md)。

## Requirements

- Java 21 or newer
- Maven 3.6.3 or newer (no wrapper; run `mvn` directly)

## Building

```bash
mvn verify    # compiles, enforces formatting, runs tests, checks nullness contracts
```

Run Maven from the repository root: Spotless resolves its config file relative to the directory
Maven was invoked from.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
