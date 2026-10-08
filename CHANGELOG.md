# Changelog

Notable changes to this project are recorded here, newest section first. The section for a
version becomes the body of the GitHub Release created when that version's tag is pushed.

## [Unreleased]

- A replayed OpenAI Responses turn keeps the order it was read in: the item the answer becomes stands where its first text or image sits, so the reasoning the model wrote before answering stays in front of it rather than after
- A streamed tool call is assembled from the frames that spell it: the frame that opens a call names the item it announces, so the argument frames that name the same item now join it instead of arriving as nameless calls of their own, and a fragment that names no item is matched by the position it reports
- A generated schema now states what the types ask for instead of nothing: a decode schema requires every property except one the type makes optional, and an `Optional` is nullable in every position — a method parameter, a property, a list item, a map value — where it used to come out as a bare object at the root and as its value type alone inside a list or a map
- An encode schema lists every property as required while the mapper writes every property and drops one an inclusion setting may leave out, and a decode schema demands every property once the mapper is set to refuse a missing creator property
- An API key is optional: leaving it unset sends no auth header, so an OpenAI-compatible server that authenticates nothing — a local runtime, say — is called as it stands instead of refused with `apiKey is required`
- Annotated tools: `@ToolMethod` and `@ToolParam` declare a tool on a method, and `MethodTools` reads a class's annotated methods into tools — any visibility, inherited and interface-default ones included — running `ToolMethodSpecCustomizer` steps between the annotations and the finished tool, and resolving `type` through a `SpecToolFactory`
- A method tool is now built from a `ToolMethodSpec` and a `JsonCodec` alone: the declaration can no longer be handed in whole — `MethodTool.of(ToolDefinition, …)`, `define(...)` and the subclass constructor are gone — and the spec settles its own declaration and refuses what it cannot, while a `SpecToolFactory` builds the tool the spec describes
- A schema is read-only: `JsonSchema` answers what a node carries and is never changed, the object form is the immutable `ObjectJsonSchema` built through `JsonSchemaBuilder`, and a change is a new schema rather than a change to this one — the JSON Schema keyword names are public in `JsonSchemaKeywords`
- The Jackson schema generator is configurable: `JacksonSchemaSettings` carries the choices — which optional types are flattened, how a property's being required is decided, and so on — the generators are built from it, and the Spring starter takes victools `Module` beans over the defaults
- A `byte[]` is described as the base64 string the mapper writes it as, rather than as an array of numbers
- A tool's argument schema and a response format's schema are carried as `JsonSchema` values rather than text: `ToolDefinition` and `ChatResponseFormat` take the schema itself, so a provider reshapes it for the wire instead of parsing a string first
- A `JsonSchema` is bound natively in the Jackson module — `Synapse4jJacksonModule` registers a serializer and a deserializer for it, with `JsonSchemas.shapesOf` naming the shape each keyword takes — so a schema now travels as a `JsonSchema` end to end: the mutable class with `toMap`/`fromMap` is gone
- A schema compares by value and prints as its own value: `ObjectJsonSchema` and `BooleanJsonSchema` implement `equals`/`hashCode`, and `toString` renders the schema's own JSON rather than a class name glued to a value
- `JacksonJsonCodec` is built from a `JsonMapper.Builder` and the two schema generators, binding its module itself, and the Spring starter rebuilds the application's mapper rather than registering a second one
- The Spring starter reads tools off annotated beans: `@Tools` marks a class — component scanning registers it, its `prefix` (also `value`) goes in front of every tool name the class declares, and its `client` picks the chat client — and `ToolsProcessor` registers those tools on the chat clients at the end of startup, failing when a class names a client no bean answers to
- `synapse4j.tools.*` binds the tool settings — `spel`, `strict`, and per-tool `methods` overrides keyed by the name a tool carries before configuration applies — and drives the starter's customizers in order: the class prefix, the configuration, a parameter marked `@Autowired`, `@Qualifier` or `@Value` filled from the container, and, with `synapse4j.tools.spel=true`, `#{...}` and `${...}` resolution in the annotation text
- `ChatClient` answers its configuration as well as taking it: `chatCustomizers()`, `defaultTools()`, `toolProviders()` and `defaultOptions()`
- `MethodTools.from(Class, Object)` reads the annotated methods off a given class and runs them on a given instance, so a bean the container wrapped in a proxy still yields its tools while the proxy stays what a call runs on
- `Synapse4jProperties` no longer sets `ignoreUnknownFields=false`, so an unknown `synapse4j.*` key is ignored rather than failing the context
- A message and its parts are values: `ChatMessage` and every `ContentPart` are built once and never change, extras frozen on the way in, so both can be shared across calls and threads; the in-place mutators are gone (`addPart`, `addText`, `getOrCreateExtras`, `ToolResultPart.addPart`/`addText`), `ChatMessage` is assembled through its builder — `ChatMessage.builder()`, `toBuilder()`, and `mapExtras(UnaryOperator)` for the extras bag — and `ProviderExtras` gains `freeze()`, `isFrozen()` and `merged(base, overlay)`
- A chat client reshapes the schemas it sends: `JsonSchemaCustomizer` takes a schema and answers the one to use — `InlineJsonSchemaCustomizer`, which replaces a `$ref` with the definition it names, is the one built in for a protocol that takes none — and `addJsonSchemaCustomizer`, `removeJsonSchemaCustomizer` and `jsonSchemaCustomizers()` register them, so every schema a request carries, each tool's arguments and the response format's, goes through them in order and the answer is cached until one is added or removed
- `DelegatingTool` stands in for another tool: it answers a declaration of its own while running the delegate untouched, and `DelegatingTool.withInputSchema(delegate, inputSchema)` presents the delegate's declaration with its argument schema replaced
- How a request body reaches the transport is a `BodyWriteMode` — `AUTO`, the new default, `STREAMED` or `BUFFERED` — named by `HttpOptions.bodyWriteMode` as a string and parsed by `BodyWriteMode.from`, which retires the `HttpOptions.STREAMED`/`BUFFERED` constants; the JDK transport refuses `STREAMED` by name and gathers every body
- `ToolDefinition` is a read-only value — final fields, no setters, a frozen extras bag — so a declaration can be handed to a tool and shared across calls without changing
- The codec seam is `JsonCodec` alone: `AbstractJsonCodec` and its `encodeValue`/`decodeValue` hooks are gone, so an implementation handles this library's own schema type itself, and `JsonCodec.convert` takes a nullable value, where `null` means absent and what absence becomes is the type's to settle
- A failure out of `JacksonJsonCodec` — binding, conversion or schema generation — is reported as a `SynapseException` naming the type it was working on, rather than as the Jackson exception underneath
- A decode schema follows the mapper on additional properties: an object that lists properties is described as closed (`additionalProperties: false`) where the mapper refuses a property the JSON leaves undeclared, and left open where it accepts one — the encode schema is untouched
- `JsonSchema` no longer answers `getItems`, which covered only one of the two forms `items` may take, and any keyword is read through `keys()`, `get(String, Class)`, `getList(...)` and `getMap(...)`, each answering only a value of the type asked for
- `synapse4j.jackson.*` binds the Jackson module's schema choices, one key per `JacksonSchemaSettings` field, so a choice the module recommends can be turned off from configuration
- `@ToolMethod(schema = …)` now reaches the declaration, as its javadoc said it did, and `MethodTools.of(...)` reads the `@ToolParam` annotations on the method's parameters the way `from(...)` does — `fromModel`, a renamed or described argument and `required` included, where an argument meant for the application used to be shown to the model
- A tool method is resolved as Java resolves it: a class's own declaration, one inherited from a superclass included, now wins over an interface default method of the same signature, where the interface's answer used to stand
- An assistant turn that only calls tools goes out without a `content` member rather than with an empty array, which OpenAI chat completions refuses
- An encode schema answers requiredness from what writing does: `@JsonProperty(required = true)` no longer makes the encode side demand a property an inclusion setting may leave out, and the inclusion a class carries, `USE_DEFAULTS` included, is read as a property's own is
- `InlineJsonSchemaCustomizer` expands a `$defs` entry in the boolean form too, where a `$ref` to one used to be kept
- The starter declares a `ToolExecutor` bean — a `DefaultToolExecutor` — and hands the loop that one, so a round cap, a worker pool or a different answer to a failed call is an application bean's to set
- `ErrorHandlers.message(prefix)` answers an exception whose message is empty, not only one that is absent, with the exception's own name, so the prefix is never left standing before nothing
- `@Tools` is read off the class the container registers rather than off the class that declares a method: a `ToolMethodSpec` now carries that class as its `owner`, so the prefix and the client come from one class, and a tool a marked class only inherits — an interface default method included — carries that class's prefix instead of none

## [0.0.2] - 2026-10-01

- A text convenience: `ChatMessage.getText()` joins a message's text parts — reasoning and the other kinds left out, an empty string when it says nothing in text — and `ChatResponse.getText()` reads the answer's text in one call
- A standing system message: `DefaultSystemMessageCustomizer` in core gives a call that carries none the framing it holds, and the starter wires one from `synapse4j.chat.system-message`
- The chat-only starter settings move under `synapse4j.chat.*`: `synapse4j.chat-client` becomes `synapse4j.chat.client`, `synapse4j.auto-tool-calling` becomes `synapse4j.chat.auto-tool-calling`, `synapse4j.system-message` becomes `synapse4j.chat.system-message`, and `synapse4j.chat-options.*` becomes `synapse4j.chat.options.*` — the family (`openai`, `anthropic`) and transport (`http-*`) keys stay at the root

## [0.0.1] - 2026-09-30

- Provider-neutral chat model: messages of text, reasoning, tool calls and media parts, with blocking and streaming calls sharing one request model and stream events mapping one-to-one onto each protocol's own frames
- Structured output: ask for JSON, or for JSON constrained to a schema your own codec generated
- Tool calling: declarations paired with executable tools, an execution policy for a batch of calls, and an optional decorator that runs the model's tool-call rounds
- Conversation continuation: `continueWith` appends an answer to a request and hands the next call back, carrying the protocol's own state where it has one
- Provider-specific fields survive a round trip: anything the library does not model is kept on the node it arrived on and written back unchanged
- Default options on a chat client, so a standing model, temperature or response format is stated once and inherited by every call
- JSON codec seam with JSON Schema generation; the Jackson implementation ships as its own module
- HTTP transports built on the JDK client, Spring's RestClient, and Apache HttpClient 5
- Protocol clients for OpenAI Chat Completions, the OpenAI Responses API, and Anthropic Messages
- Spring Boot starter with auto-configuration, bound from synapse4j.*: provider and transport config, default chat options, customizer beans and the tool-calling loop on by default
- Configuration metadata for every synapse4j.* key, so an IDE completes them
- A bilingual user guide under `docs/`: the design, getting started, and a page per feature
- A BOM for version alignment
- Java 21 baseline; null-safety contracts declared with JSpecify and enforced at compile time by NullAway
