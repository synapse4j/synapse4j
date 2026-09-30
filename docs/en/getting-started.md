# Getting started

**English** | [中文](../zh-CN/getting-started.md)

This walkthrough goes from an empty project to a working call, then adds streaming, tools and
structured output. It uses the JDK HTTP client, Jackson and OpenAI — each a module you could swap
for another without changing the rest.

## 1. Add the dependencies

Import the BOM once to align versions, then declare the modules you use.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.synapse4j</groupId>
      <artifactId>synapse4j-bom</artifactId>
      <version>0.0.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.synapse4j</groupId>
    <artifactId>synapse4j-core</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.synapse4j</groupId>
    <artifactId>synapse4j-jackson</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.synapse4j</groupId>
    <artifactId>synapse4j-http-jdk</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.synapse4j</groupId>
    <artifactId>synapse4j-openai</artifactId>
  </dependency>
</dependencies>
```

The same four, in Gradle's Kotlin DSL:

```kotlin
dependencies {
    implementation(platform("io.github.synapse4j:synapse4j-bom:0.0.1"))
    implementation("io.github.synapse4j:synapse4j-core")
    implementation("io.github.synapse4j:synapse4j-jackson")
    implementation("io.github.synapse4j:synapse4j-http-jdk")
    implementation("io.github.synapse4j:synapse4j-openai")
}
```

The `0.0.1` above is the version these docs were written against; take the current release from
Maven Central.

## 2. Build a client

A client is three pieces working together: a JSON codec that turns values into JSON, an HTTP client
that sends the bytes, and a provider client that speaks one protocol. You build each one and hand
it to the next.

```java
import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.jdk.JdkHttpClient;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.openai.OpenAiCompletionsChatClient;
import io.github.synapse4j.openai.OpenAiConfig;

JsonCodec codec = new JacksonJsonCodec();
HttpClient http = new JdkHttpClient();

OpenAiConfig config = new OpenAiConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));

ChatClient client = new OpenAiCompletionsChatClient(http, codec, config);
```

There is no container and no auto-discovery: if you want a different HTTP client or JSON library,
you pass a different instance. Nothing else in this walkthrough changes.

## 3. Give the client a standing model

A model, a temperature or a response format that every call shares belongs on the client rather
than on each request.

```java
import io.github.synapse4j.data.ChatOptions;

ChatOptions defaults = new ChatOptions();
defaults.setModel("gpt-4o-mini");
client.setDefaultOptions(defaults);
```

A request that sets the same field overrides the default; a request that leaves it alone inherits
it.

## 4. Your first call

```java
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;

ChatResponse response = client.chat(new ChatRequest()
        .systemMessage("Answer in one short sentence.")
        .addUserMessage("Why is the sky blue?"));

System.out.println(text(response));
```

The answer carries the assistant's turn as a `ChatMessage` — the same class a request is built
from. Reading the text out of it is a short walk over its parts:

```java
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;

static String text(ChatResponse response) {
    StringBuilder out = new StringBuilder();
    for (ContentPart part : response.getMessage().getParts()) {
        if (part instanceof TextPart text) {
            out.append(text.getText());
        }
    }
    return out.toString();
}
```

## 5. Continue the conversation

The library keeps no history. You keep it, by holding on to the request and folding each answer
back into it with `continueWith`:

```java
ChatRequest request = new ChatRequest()
        .systemMessage("Answer in one short sentence.")
        .addUserMessage("Why is the sky blue?");

ChatResponse first = client.chat(request);
client.continueWith(request, first);

request.addUserMessage("And why is it red at sunset?");
ChatResponse second = client.chat(request);
```

`continueWith` archives the round that just went out and appends the assistant's answer, so the
next `chat` sends the whole exchange. This is the one line you would write differently on a server
that remembers conversations for you; a client whose protocol does that overrides it, and the call
site stays the same.

If you persist the conversation yourself, implement a `ChatCustomizer` and write the request and
response out when its hook fires. The library never reaches for your storage.

## 6. Stream an answer

Streaming shares the request model with the blocking call; only the result differs.

```java
import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.data.ChatStreamEvent;

try (ChatStream stream = client.stream(request)) {
    for (ChatStreamEvent event : stream) {
        if (event.getDelta() != null) {
            for (ContentPart part : event.getDelta().getParts()) {
                if (part instanceof TextPart text) {
                    System.out.print(text.getText());
                }
            }
        }
    }
}
```

The stream assembles itself as you pull: after the loop, `stream.aggregatedResponse()` holds the
turn the blocking call would have returned. `ChatStream` is `Iterable` and
`AutoCloseable` on purpose — a `for` loop with `break` is the intended shape, and
try-with-resources closes the connection when you leave early.

## 7. Let the model call a tool

A tool is a declaration plus the code behind it. `FunctionTool` builds both from a type and a
lambda: the model's arguments are decoded into your record, the lambda runs, and its result is
rendered back.

```java
import io.github.synapse4j.chat.ToolCallingChatClient;
import io.github.synapse4j.tool.FunctionTool;
import io.github.synapse4j.tool.Tool;

record Weather(String city) {}

Tool weather = FunctionTool.of(
        "get_weather",
        "Get the current weather for a city",
        Weather.class,
        (input, context) -> "Sunny in " + input.city(),
        codec);

ChatClient toolClient = new ToolCallingChatClient(client);

ChatResponse answer = toolClient.chat(new ChatRequest()
        .addUserMessage("What's the weather in Paris?")
        .addTool(weather));

System.out.println(text(answer));
```

`ToolCallingChatClient` wraps any client: it runs the model's tool-call rounds until the model
stops asking, appending the calls and their results to the request as it goes. A bare client does
not run tools — it hands you the calls and lets you drive them, which is what you want when the
default loop is not the one you need.

## 8. Ask for structured output

The same codec that binds your values also generates the schema that constrains the model.

```java
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatResponseFormat;

record Person(String name, int age) {}

ChatResponseFormat format = new ChatResponseFormat();
format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
format.setName("person");
format.setSchema(codec.encode(codec.generateDecodeSchema(Person.class)));
format.setStrict(true);

ChatOptions options = new ChatOptions();
options.setResponseFormat(format);

ChatRequest structured = new ChatRequest().addUserMessage("Invent one person.");
structured.setOptions(options);

ChatResponse response = client.chat(structured);

Person person = codec.decode(text(response), Person.class);
```

The schema is JSON Schema text produced by your codec, so the model is constrained to exactly the
JSON your codec reads back — no second library, no hand-written schema.

## Where to go next

- [Design and trade-offs](design.md) explains what the library does and does not do, and why.
- The Javadoc is the reference for every type and method used above.
