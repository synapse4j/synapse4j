# Design and trade-offs

**English** | [中文](../zh-CN/design.md)

This page explains what synapse4j does, what it deliberately leaves out, and why. Read it if you
want to know whether the library will fit before you build on it, or if you are about to change it.

## It models and transports a call; it does not remember one

The library builds a request, sends it, and reads the answer back. That is the whole job.

It stores nothing between calls. Conversation history, where you keep it, how you trim it, and what
happens when you move a conversation from one provider to another are all decisions for your
application. The library hands you the data and stays out of the way.

This is the main difference from a framework. A framework that owns your conversation also owns how
it is stored, compacted and migrated — decisions you may want to make yourself. It is also what
lets the library be stateless: one client can serve every request in your application, with no
per-conversation instance to manage, and every call receives all of its input explicitly.

A conversation is tied together by an identifier you supply. The library passes it through and
never stores it.

## One model across providers

Providers agree on most of a call: messages with roles, a model name, a temperature, a list of
tools. That common ground is what the shared request and response types express.

The hard part is everything else, because providers do not agree on the rest. The library handles
it in four ways:

1. **Fields it does not model are kept and sent back unchanged.** Every node that carries one — a
   message, a content part, a tool — and the call's own options can carry provider-specific fields
   alongside the ones the library knows. Whatever a provider sent that the library has no name for
   is kept on the node it came from and written back when that node goes out again, so nothing is
   lost in a round trip.

2. **A field joins the shared model only when it has to survive a provider switch, and the
   providers agree on what it means.** Two providers carrying a similarly named field is not
   enough. The field has to matter across providers *and* the providers' values have to map onto
   one common meaning.

3. **A shared field a protocol cannot express is left off the wire, and the call goes on.** A
   provider that has no member for what you set simply does not send it; refusing would break the
   same code the moment you switch provider, which is the thing this library exists to prevent. The
   one exception is a requirement on the shape of the answer: a protocol that cannot honour it
   fails the call, because an answer that violates what was asked for but looks like success is the
   most expensive kind of wrong.

4. **A provider's spelling of a shared field is configuration on that module.** Endpoints disagree
   about names — one wants `max_completion_tokens`, another `max_tokens`; one carries reasoning as
   `reasoning`, another as `reasoning_content`. That name is an option on the module's config, used
   for reading and writing alike, with a default that suits the provider. It is never guessed from
   what a response happened to contain, because a conversation that reads one spelling and writes
   another would rename a member the endpoint never sent.

What travels unchanged travels unchanged: a module writes back exactly what it read. Moving a
conversation from one provider to another is a deliberate act by your application, not something
the library guesses at.

## The JSON library and the HTTP client are yours

The library never serializes a value or opens a connection itself. It asks a JSON codec to turn
values into JSON, and an HTTP client to send bytes. Both are interfaces in the core; the
implementations live in their own modules.

The alternative — depending on Jackson and the JDK client directly — would make those choices
permanent. Here you can swap either one, and neither a provider module nor your code has to change.
This is the rule behind the module split: the core states the interface, and each JSON or HTTP
library gets a module of its own.

## What a generated schema promises

A schema is generated from a Java type, and it makes one promise: whatever the schema allows, the
codec accepts. It may promise less — the schema you send to a model deliberately does — but never
more.

That promise runs in a direction. The schema you hand to whoever produces JSON — the arguments of a
tool the model calls, the shape of a structured answer — is the decode schema: what the codec is
willing to read. The schema describing what this library produced — a model told what a call returns
— is the encode schema. The two can differ, and where a binder is asymmetric they do.

The decode schema follows the types rather than the binder's leniency. A value the type makes
optional — `Optional`, `OptionalInt` and their kin — may be absent and may be null, wherever it is
written: a method parameter, a property, a list item, a map value. Everything else is required and
not nullable. A `Map` keeps its free keys, with its value type becoming `additionalProperties`. An
object that declares properties is closed against the ones it does not list — `additionalProperties:
false` — where the reading refuses a property the JSON leaves undeclared, and left open where the
reading accepts one. The required-and-nullable part is stricter than the binder, which would read an
absent `String` as null without complaining: the room a lenient binder has is simply not offered to
the producer.

The encode schema follows the mapper instead, because it describes what writing really produces.
While the mapper writes every property, every property is required; once an inclusion setting may
leave a value out, it is not.

What a provider accepts is a separate question. Some demand every property in `required`, some spell
a nullable value their own way, some honour only a subset of the keywords — and that translation
belongs to the provider module, not to the schema generator. One codec serves every provider you talk
to.

## What the API looks like, and why

**Blocking, not reactive.** A call returns when the answer is complete. Asynchrony is left to the
JDK — Java 21 virtual threads — instead of binding the library to Reactor or RxJava. This keeps the
threading model simple, and it is why nothing in the library pushes events at you.

**Streaming and non-streaming share one request.** You write the request once and choose how to
consume the answer. A streaming call hands you the provider's events as they arrive and assembles
the turn the blocking call would have returned.

**Open structures.** Types that users or providers extend are not `enum`s, `record`s or `final`
classes. An `enum` cannot gain values, and a `record` cannot carry an extra field; either would
block the extension the model exists to allow. Values that are known to grow — a role, a finish
reason — are strings with named constants. A closed form is used only where a specification fixes
the set and nothing extends it.

**No abstraction before it is needed.** A new abstraction grows out of at least two real
implementations, not a guess about a third.

**Cheaper by default.** Where the other principles leave a choice, the library takes the cheaper
one — less memory, fewer intermediate steps: a request is written as it goes rather than built as a
tree and re-serialized, and a response is read the same way.

## Non-goals

- No agent orchestration or workflow engine.
- No conversation memory. History is yours.
- No binding to a particular JSON library, HTTP client or LLM provider.

## Next

- [Getting started](getting-started.md) shows these choices in code.
