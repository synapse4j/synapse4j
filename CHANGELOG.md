# Changelog

Notable changes to this project are recorded here, newest section first. The section for a
version becomes the body of the GitHub Release created when that version's tag is pushed.

## [Unreleased]

- A generated schema now states what the types ask for instead of nothing: a decode schema requires every property except one the type makes optional, and an `Optional` is nullable in every position — a method parameter, a property, a list item, a map value — where it used to come out as a bare object at the root and as its value type alone inside a list or a map
- An encode schema lists every property as required while the mapper writes every property and drops one an inclusion setting may leave out, and a decode schema demands every property once the mapper is set to refuse a missing creator property
- An API key is optional: leaving it unset sends no auth header, so an OpenAI-compatible server that authenticates nothing — a local runtime, say — is called as it stands instead of refused with `apiKey is required`
- Annotated tools: `@ToolMethod` and `@ToolParam` declare a tool on a method, and `MethodTools` reads a class's annotated methods into tools — any visibility, inherited and interface-default ones included — running `ToolMethodSpecCustomizer` steps between the annotations and the finished tool, and resolving `type` through a `SpecToolFactory`
- A method tool is now completed from a `ToolMethodSpec` by `initialize(...)`, the `SpecTool` contract, and the resolution is validated by the tool that uses it; the declaration can no longer be handed in whole — `MethodTool.of(ToolDefinition, …)`, `define(...)` and the subclass constructor are gone, and the arguments schema beyond the per-parameter hooks is `argumentsSchema`'s to shape
- A schema is read-only: `JsonSchema` answers what a node carries and is never changed, the object form is the immutable `ObjectJsonSchema` built through `JsonSchemaBuilder`, and a change is a new schema rather than a change to this one — the JSON Schema keyword names are public in `JsonSchemaKeywords`
- The Jackson schema generator is configurable: `JacksonSchemaSettings` carries the choices — which optional types are flattened, how a property's being required is decided, and so on — the generators are built from it, and the Spring starter takes victools `Module` beans over the defaults
- A `byte[]` is described as the base64 string the mapper writes it as, rather than as an array of numbers
- A tool's argument schema and a response format's schema are carried as `JsonSchema` values rather than text: `ToolDefinition` and `ChatResponseFormat` take the schema itself, so a provider reshapes it for the wire instead of parsing a string first
- A `JsonSchema` is bound natively in the Jackson module — `Synapse4jJacksonModule` registers a serializer and a deserializer for it, with `JsonSchemas.shapesOf` naming the shape each keyword takes — and the document form is gone: `JsonSchemas.toDocument`/`fromDocument` are removed, and a schema travels as a `JsonSchema` end to end
- A schema compares by value and prints as its own value: `ObjectJsonSchema` and `BooleanJsonSchema` implement `equals`/`hashCode`, and `toString` renders the schema's own JSON rather than a class name glued to a value
- `JacksonJsonCodec` is built from a `JsonMapper.Builder` and the two schema generators, binding its module itself, and the Spring starter rebuilds the application's mapper rather than registering a second one

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
