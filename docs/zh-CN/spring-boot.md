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

starter 需要 Spring Boot 4.1 或更新版本——这是它构建与测试所针对的版本线。Boot 4.0.x 不在构建覆盖范围
内，能不能用不一定：JSON 模块需要 Jackson 3.1，而 Boot 4.0.4 是第一个管理它的版本。

## 它接了什么

默认接线是三个 bean，你自己声明同类型 bean 时各自退让：

- **`JsonCodec`**——一个 `JacksonJsonCodec`。存在 Boot 自动配置的 `JsonMapper` 时就用它，因此
  `spring.jackson.*` 和每个 `JsonMapperBuilderCustomizer` 都作用于发给模型的 schema，以及模型发回
  的 JSON。
- **`HttpClient`**——`synapse4j.http-client` 指定的传输层。
- **`ChatClient`**——`synapse4j.chat-client` 指定的协议；除非 `synapse4j.auto-tool-calling` 关掉，
  否则会包上 `ToolCallingChatClient`。

选择 Apache 传输层会多出第四个 bean，即持有连接池的 `CloseableHttpClient`。你声明自己的
`CloseableHttpClient` 或 `HttpClient` 时，它会退让。

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

`synapse4j.*` 的键按配置的内容分组：`synapse4j.openai.*` 绑定 `OpenAiConfig`，
`synapse4j.anthropic.*` 绑定 `AnthropicConfig`，`synapse4j.chat-options.*` 绑定
`ChatOptionsProperties`，即 starter 里 `ChatOptions` 的镜像，由 `toChatOptions()` 转成库里的类型
——库里的类型本身无法绑定——而 `synapse4j.http-options.*` 绑定 `HttpOptions`。每个键都是它绑定的那个
类型上的一个字段，含义在该类型上有文档；chat-options 这个镜像只重述 Spring 能绑定的字段。
`chat-options.extras` 按原始键绑定：键写的就是协议里的字段名，点分键指向嵌套成员。非字符串
的值需要 YAML——`.properties` 文件会把每个值都变成字符串。

每个 `synapse4j.*` 键都有配置元数据，因此 IDE 会补全它们。最常设置的选择项是
`synapse4j.chat-client`、`synapse4j.http-client`、`synapse4j.auto-tool-calling`，以及关掉整个自动
配置的 `synapse4j.enabled`。

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
- **`ChatClientCustomizer`** bean 在 `synapse4j.chat-options.*` 默认值之后运行，对客户端的选项与
  工具说了算：它们可以替换默认选项、注册工具或 `ToolProvider`，或添加一个 `ChatCustomizer`。

```java
@Bean
ChatClientCustomizer tenantHeader(String tenant) {
    return client -> client.addChatCustomizer(new ChatCustomizer() {
        @Override
        public void customizeRequest(ChatClient c, ChatRequest request) {
            request.getOptions().getHeaders().put("X-Tenant", tenant);
        }
    });
}
```

两类都按 `@Order` 顺序应用。

customizer 改不了提供商配置——base URL、API key、协议字段的拼写。`setConfig` 不在 `ChatClient`
接口上，而且 `auto-tool-calling` 打开时（默认如此），customizer 拿到的是 `ToolCallingChatClient`
包装，它不暴露任何可以穿透的委托对象。要改就改绑定进来的配置：starter 交给每个提供商客户端的是
`Synapse4jProperties` bean 持有的那个 `OpenAiConfig` 或 `AnthropicConfig` 实例，而客户端每次往来
都会重新读取自己的配置，所以改动那个实例会在下一次调用生效：

```java
@Component
class GatewaySettings {

    GatewaySettings(Synapse4jProperties properties) {
        properties.getOpenai().setBaseUrl("https://gateway.internal/v1");
    }
}
```

## 声明你自己的 bean

starter 定义的每个 bean 在你声明同类型 bean 时退让。声明你自己的 `ChatClient` bean，就完全手写接线；
starter 的默认随之整个让位，你也因此直接持有那个具体客户端和它自己的接口，`setConfig` 就在其中。
`JsonCodec` 与 `HttpClient` 同理。

## API key 从哪里来

starter 自己不读取任何密钥。API key 和其他属性一样进来：`synapse4j.openai.api-key` 或
`synapse4j.anthropic.api-key` 从应用自己的 Spring 配置所提供的地方绑定——环境变量的占位符、通过
`spring.config.import` 引入的 vault 或配置服务器，或任何别的属性来源。把密钥挡在应用自己的文件之外，
是应用持有的每一项凭据都要面对的同一个问题。

## 传输层

默认传输层是 Spring 的 `RestClient`，在存在 Boot 自动配置的 `RestClient.Builder` 时基于它构建——因此
为应用其余部分配置的拦截器、可观测性、SSL bundle 和 `spring.http.client.*` 设置同样作用于 LLM 调用。
`synapse4j.http-options.response-timeout` 会绑定，但在这个传输层上没有效果，因为 `RestClient` 没有
按请求的超时；改用 `spring.http.client.read-timeout`。

选择 `apache` 会使用 Apache HttpClient 5，starter 不会把它放进你的 classpath——要不要再引入一套 HTTP
栈是应用自己的决定。你需要自己声明 `httpclient5`。Apache 相关的 bean 只在对应的类存在时才生效，默认
接线根本不会碰到它们；选了 `apache` 却没带这个库时，上下文会失败，并告诉你加入
`org.apache.httpcomponents.client5:httpclient5`。
