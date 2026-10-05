# Spring Boot

**English** | [中文](../zh-CN/spring-boot.md)

The `synapse4j-spring-boot-starter` wires a complete stack into a Boot application — the Jackson
codec, a transport, and a chat client — with everything bound from `synapse4j.*` properties.

## Add the dependency

```xml
<dependency>
  <groupId>io.github.synapse4j</groupId>
  <artifactId>synapse4j-spring-boot-starter</artifactId>
  <version>0.0.2</version>
</dependency>
```

The starter needs Spring Boot 4.1 or newer — the line it is built and tested against. Boot 4.0.x is
not covered by the build and may or may not work: the JSON module needs Jackson 3.1, which Boot 4.0.4
was the first to manage.

The artifact carries the library modules the stack needs — the Jackson codec, both transports'
modules, and the OpenAI and Anthropic provider modules — so every `synapse4j.chat.client` value works
with no further dependencies. The one library it deliberately leaves off your classpath is Apache
HttpClient 5 itself; see [Transports](#transports).

## What it wires

The default wiring is three beans, each backing off if you declare your own:

- **`JsonCodec`** — a `JacksonJsonCodec`. It uses Boot's auto-configured `JsonMapper` when one
  exists, so `spring.jackson.*` and every `JsonMapperBuilderCustomizer` apply to the schemas sent
  to the model and to the JSON a model sends back.
- **`HttpClient`** — the transport `synapse4j.http-client` names.
- **`ChatClient`** — the protocol `synapse4j.chat.client` names, wrapped in `ToolCallingChatClient`
  unless `synapse4j.chat.auto-tool-calling` is off.

Selecting the Apache transport adds a fourth bean, the `CloseableHttpClient` holding the connection
pool. It steps aside when you declare a `CloseableHttpClient` or an `HttpClient` of your own.

## Configuration

```yaml
synapse4j:
  http-client: restclient         # restclient (default) | apache
  openai:
    api-key: ${OPENAI_API_KEY}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
  chat:
    client: completions           # completions (default) | responses | anthropic
    auto-tool-calling: true
    system-message: You are a concise assistant.
    options:
      model: gpt-4o-mini
      temperature: 0.2
  tools:
    spel: false
    strict: true
    methods:
      get_weather:
        description: Get the current weather for a city
        parameters:
          city:
            description: The city to look up
```

The `synapse4j.*` keys group by what they configure. The family settings — `synapse4j.openai.*`
binds `OpenAiConfig`, `synapse4j.anthropic.*` binds `AnthropicConfig` — and the transport settings —
`synapse4j.http-options.*` binds `HttpOptions` — sit at the root, because a capability shares them.
The JSON implementation's settings sit under their own key — `synapse4j.jackson.*` binds
`JacksonSchemaSettings`, the schema generator's choices — so a second implementation gets a group of
its own. Everything only a chat call has sits together under `synapse4j.chat.*`: `synapse4j.chat.client`,
`synapse4j.chat.auto-tool-calling`, `synapse4j.chat.system-message`, and `synapse4j.chat.options.*`.
Each key is a field on the type it binds, documented there; the options group restates only the
fields Spring can bind.
`synapse4j.chat.options.extras` binds raw keys: a key is the provider's own wire name, and a dotted
key addresses a nested member. A non-string value needs YAML — a `.properties` file yields a string
for every value.

The tool support has its own group, `synapse4j.tools.*`, on a `ToolsProperties`; the [Tools](#tools)
section says what each key does.

Every `synapse4j.*` key has configuration metadata, so your IDE completes them. `synapse4j.enabled`
turns the whole auto-configuration off. An unknown `synapse4j.*` key is ignored rather than failing
the context.

`synapse4j.chat.system-message` gives every call that carries no system message of its own one
saying that text; a call that states its own keeps it.

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

## Tools

The starter reads an application's tools from classes marked `@Tools`. The annotation carries
`@Component`, so component scanning registers the class as a bean — no scan of its own — and its
`@ToolMethod` methods become tools on the chat clients.

```java
@Tools(prefix = "weather_")
class WeatherTools {

    @ToolMethod(description = "Get the current weather for a city")
    String forecast(@ToolParam(name = "city") String city) {
        ...
    }
}
```

`prefix` — spelled `value` too, so `@Tools("weather_")` works — is written in front of every tool
name the class declares, which is how two classes may declare a method of one name without
colliding. It is literal text, so a separator is part of it; blank, the default, writes nothing.
`client` names the chat-client bean these tools belong to; blank, the default, puts them on every
chat client. A class naming a client no bean answers to fails startup.

A parameter marked `@Autowired`, `@Qualifier` or `@Value` is taken off the wire and filled from the
container instead of by the model, resolved the way Spring resolves any injection point — so a
qualifier picks the bean and a property is read.

`synapse4j.tools.*` binds the settings. `spel` is off by default; turning it on resolves `#{...}`
SpEL and `${...}` placeholders in the annotation text, including values written in configuration.
`strict` is the one attribute an application sets once for every tool: the value a tool falls to
when neither its annotation nor its entry under `methods` states one. `methods` holds per-tool
overrides keyed by the name the tool carries before configuration applies — the name its annotation
gave it, or, when the annotation named none, the method's own name with the class's prefix in front.
A name written in an entry does not move the entry it came from.

The starter's own steps are ordered beans; declare a `ToolMethodSpecCustomizer` of your own,
`@Order`ed, to slot in among them.

## Customizing the client

Two kinds of bean shape the auto-configured client:

- **`ChatCustomizer`** beans join its per-call hooks, so they run on every call.
- **`ChatClientCustomizer`** beans run after the `synapse4j.chat.options.*` defaults and have the
  last word on the client's options and tools: they can replace the default options, register tools
  or tool providers, or add a `ChatCustomizer`.

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

A customizer cannot change provider configuration — the base URL, the API key, the protocol's field
spellings. `setConfig` is not on the `ChatClient` interface, and with
`synapse4j.chat.auto-tool-calling` on (the default) the customizer receives the
`ToolCallingChatClient` wrapper, which exposes no delegate to reach through. Change the bound
configuration instead. Every `synapse4j.*` key is bound on a `Synapse4jProperties` bean the starter
registers, and the clients it builds read their family config from that same instance — so injecting
the bean and mutating the config it holds is how provider configuration is changed from code:

```java
@Component
class GatewaySettings {

    GatewaySettings(Synapse4jProperties properties) {
        properties.getOpenai().setBaseUrl("https://gateway.internal/v1");
    }
}
```

## Customizing the schema

`synapse4j.jackson.*` binds the Jackson module's schema choices — one flag or set per choice,
defaulting to the recommendation — and a victools `Module` bean of your own is applied to both
generators the codec is built with, after the choices. Turning a choice off and adding a module in
its place is how one of the recommended rules is replaced; [Customizing](customizing.md#the-generated-schema)
says what the choices and the modules are.

```java
@Bean
Module optionalAsItsValue() {
    // your own rule for a type the recommended choices would describe differently
    return configBuilder -> configBuilder.forTypesInGeneral().withCustomDefinitionProvider(myProvider);
}
```

Every key is a field on `JacksonSchemaSettings`, documented there. `synapse4j.jackson.*` is consulted
only while the starter builds the codec: an application that declares its own `JsonCodec` bean owns
the generators outright.

## Declaring your own bean

Every bean the starter defines backs off when you declare one of the same type. Declare your own
`ChatClient` bean to wire the client by hand; the starter's default then steps aside entirely, and
you hold the concrete client and its own surface, `setConfig` among them. The same holds for
`JsonCodec` and `HttpClient`.

## Where the API key comes from

The starter never reads a secret itself. An API key reaches it the way any other property does:
`synapse4j.openai.api-key` or `synapse4j.anthropic.api-key` is bound from whatever the application's
Spring configuration supplies — a placeholder for an environment variable, a `spring.config.import`
of a vault or a config server, or any other property source. Keeping the key out of the application's
own files is the same problem it is for every other credential the application holds.

If the endpoint needs no key — a local OpenAI-compatible server, say — leave the property unset.
The call goes out with no auth header rather than with a placeholder, and the library does not
refuse it.

## Transports

The default transport is Spring's `RestClient`, built on Boot's auto-configured `RestClient.Builder`
when one is published — so interceptors, observations, SSL bundles and `spring.http.client.*`
settings configured for the rest of the application apply to LLM calls too.
`synapse4j.http-options.response-timeout` binds but has no effect on this transport, because
`RestClient` has no per-request timeout; use `spring.http.client.read-timeout` instead.

Selecting `apache` uses Apache HttpClient 5, which the starter does not put on your classpath —
another HTTP stack is your application's choice to make. Declare `httpclient5` yourself. Selecting
`apache` without the library fails the context with a message telling you to add
`org.apache.httpcomponents.client5:httpclient5`. That failure fires only when the starter would
build the transport itself: an application that declares its own `HttpClient` has wired one by
hand, and the selector is inert for it.
