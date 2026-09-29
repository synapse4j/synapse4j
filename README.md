# synapse4j

A lightweight Java library for talking to LLM providers, decoupled from any particular framework.
You assemble your own stack — pick the JSON library, pick the HTTP client, pick the provider — and
the core abstractions never lean on any of them.

Core capabilities: JSON Schema generation and Java-object-to-schema-conforming-JSON
serialization/deserialization, a thin HTTP layer beneath a provider-neutral chat model with
blocking and streaming as equal citizens, and tool calling end to end. Provider modules ship
separately.

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
