# 入门

[English](../en/getting-started.md) | **中文**

这篇教程从空项目开始，做到一次能用的调用，再逐步加上流式、工具和结构化输出。它用的是 JDK HTTP 客户
端、Jackson 和 OpenAI——每一个都能换成别的模块，其余部分不用动。

## 1. 加入依赖

先导入一次 BOM 统一各模块版本，再声明你要用的模块。

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.synapse4j</groupId>
      <artifactId>synapse4j-bom</artifactId>
      <version>0.0.2</version>
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

同样这四个依赖，用 Gradle 的 Kotlin DSL：

```kotlin
dependencies {
    implementation(platform("io.github.synapse4j:synapse4j-bom:0.0.2"))
    implementation("io.github.synapse4j:synapse4j-core")
    implementation("io.github.synapse4j:synapse4j-jackson")
    implementation("io.github.synapse4j:synapse4j-http-jdk")
    implementation("io.github.synapse4j:synapse4j-openai")
}
```

上面写的 `0.0.2` 是本文档编写时对应的版本；实际使用时请以 Maven Central 上的最新发布为准。

## 2. 构建一个客户端

一个客户端由三部分协作而成：一个把值变成 JSON 的 JSON 编解码器，一个发送字节的 HTTP 客户端，以及一个
会说某一种协议的提供商客户端。你逐个构建，再交给下一个。

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

没有容器，也没有自动发现：想换 HTTP 客户端或 JSON 库，就传入另一个实例。本教程其余部分都不用改。

## 3. 给客户端一个固定的模型

模型、温度或响应格式如果每次调用都共用，应该放在客户端上，而不是每个请求上。

```java
import io.github.synapse4j.data.ChatOptions;

ChatOptions defaults = new ChatOptions();
defaults.setModel("gpt-4o-mini");
client.setDefaultOptions(defaults);
```

请求设置了同一个字段就覆盖默认值；不设则继承。

## 4. 第一次调用

```java
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;

ChatResponse response = client.chat(new ChatRequest()
        .systemMessage("用一句话回答。")
        .addUserMessage("天空为什么是蓝色的？"));

System.out.println(response.getText());
```

答案里助手的这一轮就是一个 `ChatMessage`——和构建请求用的是同一个类。`getText()` 把它读回来：按顺序
拼接消息里的文本部分，推理部分和其他种类不参与，得到的就是答案的文字本身。

## 5. 继续对话

库不保存历史，历史由你保存：一直拿着那个请求，每收到一个答案就用 `continueWith` 把它折回去：

```java
ChatRequest request = new ChatRequest()
        .systemMessage("用一句话回答。")
        .addUserMessage("天空为什么是蓝色的？");

ChatResponse first = client.chat(request);
client.continueWith(request, first);

request.addUserMessage("那为什么日落时是红色的？");
ChatResponse second = client.chat(request);
```

`continueWith` 把刚发出去的那一轮归档，并追加助手的回答，因此下一次 `chat` 会发出整段往来。这是唯一
一处「服务端替你记住对话」时写法不同的地方；采用那种协议的客户端会重写它，而调用点不变。

如果你想自己持久化对话，实现一个 `ChatCustomizer`，在它的钩子触发时把请求与响应写出去。
库永远不会去碰你的存储。

## 6. 流式接收答案

流式与阻塞调用共享同一个请求模型，只有结果不同。

```java
import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.data.ChatStreamEvent;

try (ChatStream stream = client.stream(request)) {
    for (ChatStreamEvent event : stream) {
        if (event.getDelta() != null) {
            System.out.print(event.getDelta().getText());
        }
    }
}
```

流在拉取过程中自行组装：循环结束后，`stream.aggregatedResponse()` 持有阻塞调用会返回的那个轮次。
`ChatStream` 刻意实现了 `Iterable` 与 `AutoCloseable`——带 `break` 的 `for`
循环就是预期的用法，提前退出时 try-with-resources 会关闭连接。

## 7. 让模型调用工具

一个工具是它的声明加上声明背后的代码。`FunctionTool` 从一个类型和一个 lambda 同时构建两者：模型的
参数被解码成你定义的 `record`，lambda 运行，返回的结果再渲染回去。

```java
import io.github.synapse4j.chat.ToolCallingChatClient;
import io.github.synapse4j.tool.FunctionTool;
import io.github.synapse4j.tool.Tool;

record Weather(String city) {}

Tool weather = FunctionTool.of(
        "get_weather",
        "查询某个城市的当前天气",
        Weather.class,
        (input, context) -> "巴黎天气晴朗",
        codec);

ChatClient toolClient = new ToolCallingChatClient(client);

ChatResponse answer = toolClient.chat(new ChatRequest()
        .addUserMessage("巴黎现在天气怎么样？")
        .addTool(weather));

System.out.println(answer.getText());
```

`ToolCallingChatClient` 包装任意客户端：它跑完模型发起的各轮工具调用，直到模型不再要求调用，并在
此过程中把调用与结果追加进请求。裸客户端不运行工具——它把调用交给你，由你驱动；当默认循环不是你想要
的循环时，这就是合适的选择。

## 8. 要求结构化输出

把你的值转成 JSON 的那个编解码器，也负责生成约束模型的 schema。

```java
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatResponseFormat;

record Person(String name, int age) {}

ChatResponseFormat format = new ChatResponseFormat();
format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
format.setName("person");
format.setSchema(codec.generateDecodeSchema(Person.class));
format.setStrict(true);

ChatOptions options = new ChatOptions();
options.setResponseFormat(format);

ChatRequest structured = new ChatRequest().addUserMessage("虚构一个人。");
structured.setOptions(options);

ChatResponse response = client.chat(structured);

Person person = codec.decode(response.getText(), Person.class);
```

schema 是由你的编解码器产出的 `JsonSchema` 值，因此模型被约束到你的编解码器恰好能读回的那份 JSON
——不用第二个库，也不用手写 schema。

## 下一步

- [设计与取舍](design.md)说明库做什么、不做什么，以及为什么。
- 上文用到的每个类型与方法的参考以 Javadoc 为准。
