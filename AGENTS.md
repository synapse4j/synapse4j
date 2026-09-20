# synapse4j

A lightweight Java library for talking to LLM providers, decoupled from any particular framework.

## What this project is

Core capabilities for interacting with LLMs: JSON Schema generation, serialization and
deserialization between Java objects and JSON that conforms to that schema, and — later —
provider chat and streaming.

It is aimed at developers who want to assemble their own stack (pick the JSON library, pick the
HTTP client, pick the provider) rather than be locked into a framework.

Baseline: **Java 21**.

## Design principles

These are hard constraints. When new code or a refactor conflicts with one of them, change the
design rather than working around it.

### 1. Minimal dependencies / dependency inversion

- Keep third-party dependencies in `synapse4j-core` to a minimum. Prefer the JDK, and add a library
  only when it earns its place — "minimal" is a matter of degree, not a ban on third-party code.
- **The public API may only mention JDK types and types owned by this library.** Types from Jackson,
  victools, HTTP clients and the like must never appear in a public signature.
- Dependencies on external capabilities (JSON, HTTP) may exist only inside implementation classes.

Rationale: a user must be able to swap the JSON library or the HTTP client without touching the
core abstractions.

### 2. Replaceable implementations, explicit wiring

- One interface may have many implementations; the user instantiates and injects the one they want.
- No SPI auto-discovery, no DI container, no global singleton.

Rationale: keep it lightweight and predictable; wiring stays in the caller's hands.

### 3. Lightweight

- Do not abstract beyond what is needed. An abstraction should grow out of at least two real
  implementations rather than be designed up front.

### 4. Efficient by default

Where the other principles leave a choice, take the cheaper one: less memory, fewer intermediate
representations, fewer passes over the same data. Do not materialize what can be passed through.

It never outranks an earlier principle, and it shapes designs rather than inviting hand-tuning.

Rationale: being lightweight and dependency-free is why someone may pick this library over a
framework; efficiency is why it would be better.

### 5. Stateless

- The library does not store or persist conversation history, and holds no state across calls. Every
  call receives all of its input explicitly.
- An ongoing conversation is represented by an identifier supplied by the caller (for example a
  session id). The library only passes it through; it does not store it.
- Corollary: implementations must be thread-safe and shareable.

### 6. Provider-neutral

- The model exposed to users is unified and provider-neutral.
- Protocol differences between providers (OpenAI chat completions / Responses, Anthropic messages,
  and so on) are expressed only inside provider modules, as a wire model plus an adapter layer. They
  must not leak into the shared model.
- Fields that are not modeled — or not yet known — must be preservable and passable through. The
  library's own structures must never block them.

### 7. Extensible core structures

- Data structures in this library **do not use `enum`, `record`, `final` classes** or other closed
  forms, so that users and providers can extend them.
- This is a deliberate exception to idiomatic Java 21. Do not "tidy up" a `record` or `enum` back
  into the code.
- Values known to grow (role, finish reason, ...) are expressed as `String` plus `static final`
  constants.

Rationale: an `enum` cannot gain values; a `record` is final and cannot carry extra fields. Either
one would block extension by users and providers.

### 8. Streaming and non-streaming are equal citizens

- Both are first-class.
- They share one public request model; their results are modeled separately.

### 9. No reactive libraries

- Asynchrony relies on JDK facilities (Java 21 virtual threads). Do not bind the library to Reactor,
  RxJava or similar.

## Non-goals

- No agent orchestration or workflow engine.
- No conversation memory (the kind of abstraction LangChain4j's `AiService` represents).
- No binding to a particular JSON library, HTTP client or LLM provider.

## Code conventions

- **Formatting is enforced by Spotless.** Run `mvn spotless:apply` right after editing Java — keep the
  tree formatted as you go rather than fixing it up later, so review sees the committed form. Do not
  hand-format. (In VS Code this already happens on save; the command is for edits made without the
  editor, and it is the fix when `verify` reports violations.) The rules come from
  `.vscode/eclipse-formatter.xml` — the Eclipse JDT formatter profile the VS Code Java extension
  ships, with three deviations recorded in the file. Block indentation is 4 spaces; line width 120.
  Note: after editing that profile, run `mvn clean` first — Spotless's freshness index does not
  notice the profile changing on its own.
- **The editor uses the same profile.** `.vscode/settings.json` points `java.format.settings.url` at
  that same file and turns on format-on-save, so VS Code and `mvn spotless:apply` produce the same
  output. Indentation is additionally pinned under `[java]`, because jdt.ls takes it from the client
  rather than from the profile. Import ordering is deliberately not enforced by either side.
- **All comments are in English** — Javadoc (including on private members), inline comments and
  TODOs. Comments explain *why*; do not restate what the code does.
- **Use Lombok instead of hand-writing boilerplate**, and only its stable annotations — nothing from
  `lombok.experimental`.
- Package names are `io.github.synapse4j.*`. Implementation classes live in a `.<vendor>` subpackage
  naming their technology origin (for example `...victools`, `...jackson`).

## Build and test

- Compile: `mvn -q -DskipTests package`
- Full check — compiles, enforces formatting, runs tests: `mvn verify`
- Tests only: `mvn test`
- There is no Maven wrapper; use `mvn` directly. Run Maven from the repository root: Spotless
  resolves its config file relative to the directory Maven was invoked from.
