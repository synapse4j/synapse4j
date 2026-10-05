# Spring Boot

[English](../en/spring-boot.md) | **中文**

`synapse4j-spring-boot-starter` 把一整套栈接进 Spring Boot 应用——Jackson 编解码器、一个传输层、一个
聊天客户端——全部从 `synapse4j.*` 属性绑定。

## 加入依赖

```xml
<dependency>
  <groupId>io.github.synapse4j</groupId>
  <artifactId>synapse4j-spring-boot-starter</artifactId>
  <version>0.0.2</version>
</dependency>
```

starter 需要 Spring Boot 4.1 或更新版本——这是它构建与测试所依据的版本。Boot 4.0.x 不在构建覆盖范围
内，能不能用没有保证：JSON 模块需要 Jackson 3.1，而 Boot 4.0.4 是第一个把 Jackson 3.1 纳入依赖管理
的版本。

## 它接了什么

默认接线是三个 bean，你自己声明同类型 bean 时各自让位：

- **`JsonCodec`**——一个 `JacksonJsonCodec`。存在 Boot 自动配置的 `JsonMapper` 时就用它，因此
  `spring.jackson.*` 和每个 `JsonMapperBuilderCustomizer` 都作用于发给模型的 schema，以及模型发回
  的 JSON。
- **`HttpClient`**——`synapse4j.http-client` 指定的传输层。
- **`ChatClient`**——`synapse4j.chat.client` 指定的协议；除非 `synapse4j.chat.auto-tool-calling`
  关掉，否则会包上 `ToolCallingChatClient`。

选择 Apache 传输层会多出第四个 bean，即持有连接池的 `CloseableHttpClient`。你声明自己的
`CloseableHttpClient` 或 `HttpClient` 时，它会退让。

## 配置

```yaml
synapse4j:
  http-client: restclient         # restclient（默认） | apache
  openai:
    api-key: ${OPENAI_API_KEY}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
  chat:
    client: completions           # completions（默认） | responses | anthropic
    auto-tool-calling: true
    system-message: 你是一个简洁的助手。
    options:
      model: gpt-4o-mini
      temperature: 0.2
```

`synapse4j.*` 的键按配置的内容分组。各厂商的设置——`synapse4j.openai.*` 绑定 `OpenAiConfig`，
`synapse4j.anthropic.*` 绑定 `AnthropicConfig`——和传输层的设置——`synapse4j.http-options.*` 绑定
`HttpOptions`——放在根上，因为同一项能力在各家之间共用这些设置。JSON 实现自己的设置放在它自己的键下
——`synapse4j.jackson.*` 绑定 `JacksonSchemaSettings`，即 schema 生成器的各项选择——这样将来换一个
实现就有一组自己的键。只有 chat 调用才有的东西收在 `synapse4j.chat.*`
下面：`synapse4j.chat.client`、`synapse4j.chat.auto-tool-calling`、`synapse4j.chat.system-message`，
以及 `synapse4j.chat.options.*`，它绑定 `ChatOptionsProperties`，即 starter 里 `ChatOptions` 的镜像，
由 `toChatOptions()` 转成库里的类型——库里的类型本身无法绑定。每个键都是它绑定的那个类型上的一个
字段，含义在该类型上有文档；options 这个镜像只重述 Spring 能绑定的字段。
`synapse4j.chat.options.extras` 按原始键绑定：键用的就是提供商的协议字段名，点分键指向嵌套成员。
非字符串的值需要 YAML——`.properties` 文件会把每个值都变成字符串。

每个 `synapse4j.*` 键都有配置元数据，因此 IDE 会补全它们。`synapse4j.enabled` 会关掉整个自动配置。

`synapse4j.chat.system-message` 会为每个没有自带系统消息的调用补上一条内容为该文本的系统消息；
自带系统消息的调用保持原样。

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
- **`ChatClientCustomizer`** bean 在 `synapse4j.chat.options.*` 默认值之后运行，对客户端的选项与
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
接口上，而且 `synapse4j.chat.auto-tool-calling` 打开时（默认如此），customizer 拿到的是
`ToolCallingChatClient` 包装，它不暴露任何可以穿透的委托对象。要改就改绑定进来的配置：starter
交给每个提供商客户端的是 `Synapse4jProperties` bean 持有的那个 `OpenAiConfig` 或 `AnthropicConfig`
实例，而客户端每次往来都会重新读取自己的配置，所以改动那个实例会在下一次调用生效：

```java
@Component
class GatewaySettings {

    GatewaySettings(Synapse4jProperties properties) {
        properties.getOpenai().setBaseUrl("https://gateway.internal/v1");
    }
}
```

## 定制 schema

`synapse4j.jackson.*` 绑定 Jackson 模块的 schema 各项选择——每个选择是一个开关或一组设置，默认就是
推荐值；你自己写的 victools `Module` bean 会在这些选择之后，挂到 codec 用的两个生成器上。把某个选择
关掉、再挂一个模块进去，就是替换推荐规则的做法；这些选择和模块分别是什么，见
[定制](customizing.md#生成的-schema)。

```java
@Bean
Module optionalAsItsValue() {
    // 针对推荐选择会描述得不一样的类型，给出你自己的规则
    return configBuilder -> configBuilder.forTypesInGeneral().withCustomDefinitionProvider(myProvider);
}
```

每个键都是 `JacksonSchemaSettings` 上的一个字段，含义写在那里。`synapse4j.jackson.*` 只在 starter
组装 codec 时读取：自己声明 `JsonCodec` bean 的应用，两个生成器完全由它掌握。

## 声明你自己的 bean

starter 定义的每个 bean 在你声明同类型 bean 时退让。声明你自己的 `ChatClient` bean，客户端就完全由
你手工接线；starter 的默认随之整个让位，你也因此直接持有那个具体客户端和它自己的完整接口，
`setConfig` 就在其中。`JsonCodec` 与 `HttpClient` 同理。

## API key 从哪里来

starter 自己不读取任何密钥。API key 和其他属性一样进来：`synapse4j.openai.api-key` 或
`synapse4j.anthropic.api-key` 从应用自己的 Spring 配置所提供的地方绑定——环境变量的占位符、通过
`spring.config.import` 引入的 vault 或配置服务器，或任何别的属性来源。把密钥挡在应用自己的文件之外，
是应用持有的每一项凭据都要面对的同一个问题。

如果端点本来就不需要密钥——比如本地的 OpenAI 兼容服务——就不要设这个属性。调用会不带鉴权头发出，
而不是塞一个占位值，库也不会因此拒绝这次调用。

## 传输层

默认传输层是 Spring 的 `RestClient`，在存在 Boot 自动配置的 `RestClient.Builder` 时基于它构建——因此
为应用其余部分配置的拦截器、可观测性、SSL bundle 和 `spring.http.client.*` 设置同样作用于 LLM 调用。
`synapse4j.http-options.response-timeout` 会绑定，但在这个传输层上没有效果，因为 `RestClient` 没有
按请求的超时；改用 `spring.http.client.read-timeout`。

选择 `apache` 会使用 Apache HttpClient 5，starter 不会把它放进你的 classpath——要不要再引入一套 HTTP
栈是应用自己的决定。你需要自己声明 `httpclient5`。Apache 相关的 bean 只在对应的类存在时才生效，默认
接线根本不会碰到它们；选了 `apache` 却没带这个库时，上下文会失败，并告诉你加入
`org.apache.httpcomponents.client5:httpclient5`。这个失败只在 starter 需要自己构建传输层时发生：
声明了自己的 `HttpClient` 的应用已经手工接好线，选择项对它不起作用。
