# synapse4j

A lightweight Java library for talking to LLM providers, decoupled from any particular framework.

## What this project is

Core capabilities for interacting with LLMs: JSON Schema generation, serialization and
deserialization between Java objects and JSON that conforms to that schema; a thin HTTP layer
beneath a provider-neutral chat model, with blocking and streaming as equal citizens; and tool
calling end to end — declarations paired with the code behind them, an execution policy for a
batch of calls, and an optional decorator that runs the model's tool-call rounds. Provider modules
ship separately.

It is aimed at developers who want to assemble their own stack (pick the JSON library, pick the
HTTP client, pick the provider) rather than be locked into a framework.

Baseline: **Java 21**.

## What belongs in this file

A rule earns a place here when it is settled and expected to hold for years: a principle that shapes
new code, or a convention that keeps the tree consistent. Anything narrower — how one class works,
why a field is spelled as it is, what was decided about one knob — belongs with the code: in javadoc,
in the name of the test that pins it, or in the commit that made the decision. One sentence where a
reader will need it beats a paragraph here, and a file that grows with every incident stops being
read.

## Design principles

These are hard constraints. When new code or a refactor conflicts with one of them, change the
design rather than working around it.

### 1. Minimal dependencies / dependency inversion

- Keep third-party dependencies in `synapse4j-core` to a minimum. Prefer the JDK, and add a library
  only when it earns its place — "minimal" is a matter of degree, not a ban on third-party code.
- **The public API may mention JDK types, types owned by this library, and types from the
  dependencies this module declares.** Exposing a library a module already depends on is not a
  problem — the discipline is how few dependencies there are, not hiding them. What a user must
  stay free to swap is carried by the rule below: JSON and HTTP libraries live only in
  implementation classes, so core's abstractions never lean on them.
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
- What travels untouched is passed through, never translated: a module writes what it read, and what
  another provider would make of it is the application's decision.
- A concept is promoted from that bag into the shared model only when the application needs it to
  survive a provider switch *and* the providers' shapes map onto one neutral value space. Two
  providers carrying a similarly named field is not a reason.
- A promoted concept a protocol cannot express is left unsent and the call goes on — refusing one
  would break the same code the moment a provider is swapped, and most protocols simply lack the
  word. The one exception is a requirement on the answer's shape: a module whose protocol cannot
  honour it fails the call, because an answer that violates what was asked for but looks like success
  is the most expensive kind of wrong. A value the protocol takes as it stands goes as it stands — the
  constants name what means the same thing across providers, and a module invents no whitelist the
  protocol never fixed.
- A provider's spelling of a common field is a configuration field on that module's config, used for
  reading and writing alike, with a default that suits the provider that fails loudly when wrong. It
  is never inferred from what a response happened to contain.

### 7. Extensible core structures

- Structures that users or providers extend **do not use `enum`, `record`, `final` classes** or
  other closed forms.
- A closed form is allowed where the set is fixed by a specification and nothing extends it — the
  token kinds a JSON reader returns, for instance. Say why where it is defined.
- This is a deliberate exception to idiomatic Java 21. Do not "tidy up" an extensible structure into
  a `record` or an `enum`.
- Values known to grow (role, finish reason, ...) are expressed as `String` plus `static final`
  constants.

Rationale: an `enum` cannot gain values; a `record` is final and cannot carry extra fields. Either
one would block extension by users and providers.

### 8. Streaming and non-streaming are equal citizens

- Both are first-class.
- They share one public request model; their results are modeled separately.

### 9. Blocking, not reactive

- The API is deliberately blocking, so the threading model stays simple: asynchrony relies on JDK
  facilities (Java 21 virtual threads). Do not bind the library to Reactor, RxJava or similar.
- Thread safety is declared, never assumed: a method is safe to call from any thread only where its
  javadoc says so. Every other method is simply not declared safe — read that as "no promise", not
  as "unsafe".

## Non-goals

- No agent orchestration or workflow engine.
- No conversation memory (the kind of abstraction LangChain4j's `AiService` represents).
- No binding to a particular JSON library, HTTP client or LLM provider.

## Code conventions

- **Formatting is enforced by Spotless.** Run `mvn spotless:apply` right after editing Java — keep the
  tree formatted as you go rather than fixing it up later, so review sees the committed form. Do not
  hand-format. The profile is `.vscode/eclipse-formatter.xml`, the one the editor also uses, so both
  sides produce the same output; import ordering is deliberately not enforced by either.
- **All comments are in English** — Javadoc (including on private members), inline comments and
  TODOs. Comments explain *why*; do not restate what the code does.
- **Use Lombok instead of hand-writing boilerplate**, and only its stable annotations — nothing from
  `lombok.experimental`. Constructors count: one that only assigns its parameters is
  `@RequiredArgsConstructor`/`@AllArgsConstructor`/`@NoArgsConstructor`, with `@NonNull` where a null
  must be refused — hand-writing it is a defect to fix on sight. Hand-write one only for what Lombok
  cannot say: a `super` call with arguments, a derived value, validation beyond `@NonNull`,
  delegation — never to carry javadoc, which goes on the class or the field.
- Package names are `io.github.synapse4j.*`. Implementation classes live in a `.<vendor>` subpackage
  naming their technology origin (for example `...victools`, `...jackson`).
- **Group types by concept, not by dependency direction.** `...data` holds the inert call model —
  structures and constant vocabularies, nothing that reaches the world. Types with behavior live in
  their domain's package: `Tool` and `ToolDefinition` are in `...tool` even though `ChatRequest`, in
  `...data`, references `Tool`. Cycles among these peer packages are accepted when they mirror a real
  relationship (a request carries a tool; the client drives a tool loop): this is one module, where a
  cycle costs the reader nothing, and a concept-wrong home would cost on every read.
- **Nullness is declared where the type permits it.** A field with an initializer carries `@NonNull`
  freely, no-args constructor or not: nothing can leave it null, and the check Lombok puts in the
  setter is the whole contract. A field *without* an initializer may not, in a class that has such a
  constructor, explicit or implicit — Lombok's check lives in setters and in the constructors that
  take the field, never in that one, so the annotation would be false from the first `new`.
  `@Nullable` marks exactly what the type can produce and nothing else, because it
  is the one form of the contract a user's IDE, checker or Kotlin compiler reads. Both on one value is
  a contradiction.
- **A null is answered where it crosses, and never re-detected.** A check the next statement
  dereferences anyway says only what the NPE would have said a day later. A parameter that must not be
  null carries Lombok's `@NonNull`. A null that would otherwise travel on silently — stored, returned,
  handed to code that treats absence as a value — is refused where it crosses, naming what answered
  null. A documented "returns null when …" is a contract, and no check contradicts it. Neither is a
  value a contract says is non-null checked again: believing the contract is what the annotation is
  for, and if it cannot be believed the declaration is the thing to fix. What is answered at a
  crossing is the null that would otherwise travel on silently, whoever wrote the value.
- **Tests pin decisions, not plumbing.** A test earns its place by pinning a decision that could go
  wrong by mistake later — merge and ordering rules, contracts (a null answered loudly, a request
  handed on unchanged, one iterator pass), failure paths — and the assertion itself must be
  defensible: a test that faithfully records a bug is worse than no test at all. An assertion that
  can be re-derived by reading the code beside it proves the source works, not us. One test per
  decision, never one per parameter, field or case, and a
  method of a few straight-line statements earns none: a test is paid for twice, once written and
  again on every later change, so name the wrong future change it would catch, and if there is none,
  write nothing. Coverage is not measured in this build.

## Documentation

- User-facing docs live under `docs/`, one mirrored tree per language — `docs/en/` and
  `docs/zh-CN/` today, more as they come. English is the source of truth; a translated tree may
  lag behind it.
- Every doc opens with a language switcher line linking to its counterparts in the other trees.
  `README.md` and `README.zh-CN.md` are the front doors and link into the trees.
- Plain Markdown, rendered by GitHub. Diagrams use Mermaid. Do not add a site generator.
- Write for a developer who knows LLM APIs, JSON, HTTP and build tools but nothing about this
  library. Do not explain or analogize the basics; do not open with this library's types or design
  vocabulary — name a thing only once the prose around it has made clear what it is. Plain, direct
  sentences, no essay flourishes.
- Every language's docs read as that language: the prose is native, and a foreign word appears only
  for code identifiers and genuine terms (HTTP, JSON, schema, JDK, class names, Java keywords,
  product names). Write each tree; do not translate another tree's text word for word — a literal
  rendering such as "写到线上" for "to the wire" reads as nonsense.
- Do not restate Javadoc, the module table or the config keys in the docs — link to them. This
  file states principles in brief; `docs/*/design.md` carries the long reasoning.

## Build and test

- The build enforces its own floor first: JDK 21+ and Maven 3.6.3+ (maven-enforcer-plugin), so
  an old toolchain fails with a message instead of deep inside javac.
- Compile: `mvn -q -DskipTests package`
- Full check — compiles, enforces formatting, runs tests: `mvn verify`
- The build also enforces the nullness contracts: NullAway runs inside the compiler (as an Error
  Prone plugin, from `.mvn/jvm.config`'s exports), so handing null to a parameter that is not
  nullable, or answering a non-null method with null, fails the build. Tests are left out of the
  analysis on purpose — several of them pass null to pin a refusal.
- Tests only: `mvn test`
- There is no Maven wrapper; use `mvn` directly. Run Maven from the repository root: Spotless
  resolves its config file relative to the directory Maven was invoked from.
