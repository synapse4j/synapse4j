# synapse4j

[English](../en/index.md) | **中文**

一个用来调用 LLM 提供商的轻量级 Java 库。

你只写一次调用——消息、模型、工具——它就能对着你指定的任意提供商工作。JSON 库、HTTP 客户端和提供商
都由你来选，库只提供中间那层模型，别的什么都不管。

```java
JsonCodec codec = new JacksonJsonCodec();
HttpClient http = new JdkHttpClient();

OpenAiConfig config = new OpenAiConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));

ChatClient client = new OpenAiCompletionsChatClient(http, codec, config);

ChatResponse response = client.chat(new ChatRequest()
        .addUserMessage("天空为什么是蓝色的？"));

System.out.println(response.getMessage().getParts());
```

## 它和别的库有什么不同

多数 Java LLM 库会把一整套东西一起塞给你：JSON 库、HTTP 客户端、保存对话历史的方式，有时还有一整个
框架。synapse4j 一个都不绑定。

- **所有提供商共用一套请求与响应模型。** OpenAI、Anthropic 等等都通过同样的类型访问。换提供商只换
  你构建的那个客户端，周围的代码不动。
- **JSON 库由你选。** 库自己不序列化任何东西，而是交给你传入的编解码器。Jackson 只是一种实现，不是
  硬性要求。
- **HTTP 客户端由你选。** JDK 自带的、Spring 的 `RestClient`、Apache HttpClient 5，或者你自己写的。
- **阻塞与流式都是一等公民**，且共用同一个请求。
- **工具调用是内置的。** 声明一个工具，配上它背后的代码，让库去跑模型的各轮工具调用。
- **无状态。** 库不跨调用保存任何东西；对话历史由你保存，存在哪里由你决定。

## 阅读顺序

1. [入门](getting-started.md)——几分钟跑通一次调用，然后是流式、工具与结构化输出。
2. [设计与取舍](design.md)——库做什么、不做什么，以及为什么。
3. *与其他 Java 库的对比——待补。*

API 本身的参考以 Javadoc 为准。

## 模块

按需选择模块；BOM 负责对齐它们的版本。

| 模块 | 是什么 |
|---|---|
| `synapse4j-core` | 请求/响应模型、JSON 与 HTTP 接口、工具调用 |
| `synapse4j-jackson` | 基于 Jackson 与 victools 的 `JsonCodec` |
| `synapse4j-http-jdk` | 基于 JDK `java.net.http` 的 `HttpClient` |
| `synapse4j-http-apache` | 基于 Apache HttpClient 5 的 `HttpClient` |
| `synapse4j-http-restclient` | 基于 Spring `RestClient` 的 `HttpClient` |
| `synapse4j-openai` | OpenAI Chat Completions 与 Responses 协议 |
| `synapse4j-anthropic` | Anthropic Messages 协议 |
| `synapse4j-spring-boot-starter` | Spring Boot 自动配置 |
| `synapse4j-bom` | 用于对齐版本的 BOM |

## 环境要求

- Java 21 或更新版本。
- 若要构建项目本身，需要 Maven 3.6.3 或更新版本（没有 Maven Wrapper，直接运行 `mvn`）。

## 许可证

Apache License 2.0——见 [LICENSE](../../LICENSE)。
