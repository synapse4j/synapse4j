# Changelog

Notable changes to this project are recorded here, newest section first. The section for a
version becomes the body of the GitHub Release created when that version's tag is pushed.

## [Unreleased]

## [0.0.1]

- Provider-neutral chat model: blocking and streaming calls share one request model, and stream events map one-to-one onto each protocol's own frames
- Tool calling: declarations paired with executable tools, an execution policy for a batch of calls, and an optional decorator that runs the model's tool-call rounds
- Default options on a chat client, so a standing model, temperature or response format is stated once and inherited by every call
- JSON codec seam with JSON Schema generation; the Jackson implementation ships as its own module
- HTTP transports built on the JDK client, Spring's RestClient, and Apache HttpClient 5
- Protocol clients for OpenAI Chat Completions, the OpenAI Responses API, and Anthropic Messages
- Spring Boot starter with auto-configuration, bound from synapse4j.*: provider and transport config, default chat options, customizer beans and the tool-calling loop on by default
- Configuration metadata for every synapse4j.* key, so an IDE completes them
- A BOM for version alignment
- Null-safety contracts declared with JSpecify and enforced at compile time by NullAway
