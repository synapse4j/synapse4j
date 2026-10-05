# Customizing

**English** | [中文](../zh-CN/customizing.md)

The library is meant to be assembled and adjusted, not adopted whole. This page is where the
adjustment points are.

## Swapping the JSON library or the HTTP client

Both are interfaces in the core, with implementations in their own modules. To use a different one,
instantiate it and pass it to the client:

```java
JsonCodec codec = new JacksonJsonCodec();
HttpClient http = new RestClientHttpClient(restClient);   // or JdkHttpClient, ApacheHttpClient
ChatClient client = new OpenAiCompletionsChatClient(http, codec, config);
```

Nothing in a provider module or in your code changes. Writing an implementation of your own is
covered by the Javadoc on `JsonCodec` and `HttpClient`.

## The generated schema

The codec generates the JSON Schema for a tool's arguments and for a structured answer, from the
Java type and the same `JsonMapper` that binds the JSON. The machinery underneath is the victools
jsonschema-generator library — a dependency of the Jackson module, so its types are on your
classpath the moment `synapse4j-jackson` is. `SchemaGenerator`, `SchemaGeneratorConfigBuilder` and
`Module` are all victools types, from the `com.github.victools.jsonschema.generator` package.

Which of the Jackson module's recommended choices to apply is a `JacksonSchemaSettings` — one flag
or set per choice, defaulting to the recommendation. A choice turned off is simply not applied,
which is how you replace it: turn it off and add a victools `Module` of your own in its place.

```java
JsonMapper mapper = JsonMapper.builder().build();

JacksonSchemaSettings settings = new JacksonSchemaSettings();
settings.setFlattenOptionals(false);            // leave the recommended choice out

SchemaGeneratorConfigBuilder builder =
        JacksonSchemaConfigBuilders.encodeSchemaConfigBuilder(mapper, settings);
builder.with(myOptionalModule);                 // put your own rule in its place

JsonCodec codec = new JacksonJsonCodec(mapper.rebuild(), new SchemaGenerator(builder.build()),
        new SchemaGenerator(JacksonSchemaConfigBuilders.decodeSchemaConfigBuilder(mapper, settings).build()));
```

The settings' Javadoc names every choice and what its default does; each module's Javadoc says what
it contributes. `null` settings applies none of the choices, leaving victools' plain configuration
for a caller who would rather compose everything.

## Standing configuration on the client

A model, a temperature or a response format that every call shares belongs on the client:

```java
ChatOptions defaults = new ChatOptions();
defaults.setModel("gpt-4o-mini");
client.setDefaultOptions(defaults);
```

The defaults fill in what a call leaves unstated: a field the call leaves `null` takes the default's
value, and the two extras bags merge with the call's entries winning by key. Setting is
configuration, meant for before the client is shared.

Tools can stand on the client too — registered ones, and providers asked on every call:

```java
client.addDefaultTool(weather);
client.addToolProvider((c, request) -> List.of(weather));
```

## Hooks around an exchange

A `ChatCustomizer` runs at one or more steps of an exchange: `customizeRequest` before the request
goes out, `customizeResponse` on the way back, `customizeStreamEvent` on each event of a stream.
Each hook is handed the value the client already holds and changes it in place.

```java
client.addChatCustomizer(new ChatCustomizer() {
    @Override
    public void customizeRequest(ChatClient client, ChatRequest request) {
        request.getOptions().getHeaders().put("X-Tenant", tenant);
    }
});
```

Customizers run in the order they were added, and a hook a customizer does not override does
nothing. A client shared across threads hands each call a consistent list, so a customizer must
itself be safe to run concurrently.

## A customizer the library provides

`DefaultSystemMessageCustomizer` is a `ChatCustomizer` that gives every request carrying no system
message of its own one saying the text it was built with. Register it like any other:

```java
client.addChatCustomizer(new DefaultSystemMessageCustomizer("Answer in one short sentence."));
```

A request that states its own system message keeps it — the standing one only fills the gap. The
Spring Boot starter wires one from `synapse4j.chat.system-message`.

## Per-call HTTP settings

`ChatOptions` carries HTTP-level settings for one call, beside the client's own:

```java
HttpOptions http = new HttpOptions();
http.setResponseTimeout(Duration.ofSeconds(30));

ChatOptions options = new ChatOptions();
options.setHttpOptions(http);
options.getHeaders().put("X-Request-Id", id);
```

`HttpOptions` holds the per-call HTTP settings: a timeout for the response headers (which does not
bound reading the body), how a written body reaches a transport that cannot take one as it comes,
and a server-sent event frame budget. The class's Javadoc names the fields and the values they take
— the body-write mode, in particular, is the string `"streamed"` or `"buffered"`, not an enum.

Each implementation carries its own `HttpOptions` too, passed as the second constructor argument —
`new JdkHttpClient(delegate, options)` and its counterparts in the other HTTP modules. The request's
settings merge with the implementation's by the same fill-in-what-is-null rule as the call options:
a field the request leaves unset takes the implementation's value, and one the implementation never
set falls back to `HttpOptions.defaults()`.
