# Spring Boot

[English](../en/spring-boot.md) | **中文**

`synapse4j-spring-boot-starter` 把一整套栈接进 Spring Boot 应用——Jackson 编解码器、一个传输层、一个
聊天客户端——全部从 `synapse4j.*` 属性绑定。

## 加入依赖

```xml
<dependency>
  <groupId>io.github.synapse4j</groupId>
  <artifactId>synapse4j-spring-boot-starter</artifactId>
  <version>0.0.1</version>
</dependency>
```

## 它接了什么

三个 bean，你自己声明同类型 bean 时各自退让：

- **`JsonCodec`**——一个 `JacksonJsonCodec`。存在 Boot 自动配置的 `JsonMapper` 时就用它，因此
  `spring.jackson.*` 和每个 `JsonMapperBuilderCustomizer` 都作用于发给模型的 schema，以及模型发回
  的 JSON。
- **`HttpClient`**——`synapse4j.http-client` 指定的传输层。
- **`ChatClient`**——`synapse4j.chat-client` 指定的协议；除非 `synapse4j.auto-tool-calling` 关掉，
  否则会包上 `ToolCallingChatClient`。

## 配置

```yaml
synapse4j:
  chat-client: completions        # completions（默认） | responses | anthropic
  http-client: restclient         # restclient（默认） | apache
  auto-tool-calling: true
  openai:
    api-key: ${OPENAI_API_KEY}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
  chat-options:
    model: gpt-4o-mini
    temperature: 0.2
```

| 分组 | 绑定到什么 |
|---|---|
| `synapse4j.openai.*` | `OpenAiConfig`：`base-url`、`api-key`、`organization`、`project`、`max-tokens-field`、`reasoning-field`、`store-responses` |
| `synapse4j.anthropic.*` | `AnthropicConfig`：`base-url`、`api-key`、`anthropic-version` |
| `synapse4j.chat-options.*` | 默认的 `ChatOptions`：`model`、`temperature`、`max-output-tokens`、`top-p`、`reasoning-effort`、`tool-choice`、`tool-choice-name`、`response-format.*`、`headers.*`、`extras.*` |
| `synapse4j.http-options.*` | `HttpOptions`：`body-write-mode`、`response-timeout`、`max-frame-bytes` |

`chat-options.extras` 按原始键绑定：键是提供商自己的线上名字，点分键指向嵌套成员。非字符串的值需要
YAML——`.properties` 文件会把每个值都变成字符串。

每个 `synapse4j.*` 键都有配置元数据，因此 IDE 会补全它们。

## 使用

```java
@Service
class Assistant {

    private final ChatClient client;

    Assistant(ChatClient client) {
        this.client = client;
    }

    ChatResponse ask(String question) {
        return client.chat(new ChatRequest().addUserMessage(question));
    }
}
```

## 定制客户端

两类 bean 塑造自动配置的客户端：

- **`ChatCustomizer`** bean 加入它的每次调用钩子，因此每次调用都会运行。
- **`ChatClientCustomizer`** bean 在 `synapse4j.chat-options.*` 默认值之后运行，拥有最后一票：它们
  可以替换默认选项、注册工具或 `ToolProvider`，或添加一个 `ChatCustomizer`。

两类都按 `@Order` 顺序应用。

## 声明你自己的 bean

starter 定义的每个 bean 在你声明同类型 bean 时退让。声明你自己的 `ChatClient` bean，就完全手写接线；
starter 的默认随之整个让位。`JsonCodec` 与 `HttpClient` 同理。

## 传输层

默认传输层是 Spring 的 `RestClient`，在存在 Boot 自动配置的 `RestClient.Builder` 时基于它构建——因此
为应用其余部分配置的拦截器、可观测性、SSL bundle 和 `spring.http.client.*` 设置同样作用于 LLM 调用。
`synapse4j.http-options.response-timeout` 会绑定，但在这个传输层上没有效果，因为 `RestClient` 没有
按请求的超时；改用 `spring.http.client.read-timeout`。

选择 `apache` 会使用 Apache HttpClient 5，starter 不会把它放进你的 classpath。你需要自己声明
`httpclient5`；没有它时，只有那一个 bean 在启动时失败。
