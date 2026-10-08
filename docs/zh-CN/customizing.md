# 定制

[English](../en/customizing.md) | **中文**

这个库是用来组装和调整的，而不是整套照搬。本文说明有哪些调整点。

## 更换 JSON 库或 HTTP 客户端

两者都是 core 里的接口，实现在各自的模块里。想换一个，就实例化它并交给客户端：

```java
JsonCodec codec = new JacksonJsonCodec();
HttpClient http = new RestClientHttpClient(restClient);   // 或 JdkHttpClient、ApacheHttpClient
ChatClient client = new OpenAiCompletionsChatClient(http, codec, config);
```

提供商模块和你的代码都不用改。自己写一个实现，参考 `JsonCodec` 与 `HttpClient` 上的 Javadoc。

## 生成的 schema

编解码器为工具的入参和结构化答案生成 JSON Schema，来源是 Java 类型，以及绑定 JSON 用的同一个
`JsonMapper`。底层机制是 victools 的 jsonschema-generator 库——它是 Jackson 模块的一个依赖，所以
一旦引入 `synapse4j-jackson`，它的类型就出现在 classpath 上。`SchemaGenerator`、
`SchemaGeneratorConfigBuilder` 和 `Module` 都是 victools 的类型，来自
`com.github.victools.jsonschema.generator` 包。

要应用 Jackson 模块推荐选择中的哪些，由 `JacksonSchemaSettings` 决定——每个选择对应一个开关或一组
设置，默认就是推荐的做法。关掉某个选择，就等于不应用它，而这正是替换它的方式：关掉它，再在它的位置
挂上你自己的 victools `Module`。

```java
JsonMapper mapper = JsonMapper.builder().build();

JacksonSchemaSettings settings = new JacksonSchemaSettings();
settings.setFlattenOptionals(false);            // 不应用推荐的那个选择

SchemaGeneratorConfigBuilder builder =
        JacksonSchemaConfigBuilders.encodeSchemaConfigBuilder(mapper, settings);
builder.with(myOptionalModule);                 // 换成你自己的规则

JsonCodec codec = new JacksonJsonCodec(mapper.rebuild(), new SchemaGenerator(builder.build()),
        new SchemaGenerator(JacksonSchemaConfigBuilders.decodeSchemaConfigBuilder(mapper, settings).build()));
```

设置类的 Javadoc 列出了每个选择及其默认行为；每个模块的 Javadoc 说明它贡献了什么。`settings` 传
`null` 就是一个选择都不应用，把 victools 的纯净配置留给宁愿全部自己组装的人。

## 为协议重塑 schema

生成出来的是类型所表达的内容；而某个协议可能希望 schema 是另一种形状，或者不接受其中的一部分。
`JsonSchemaCustomizer` 接收一个 schema，返回要用的那个；客户端会让已注册的 customizer 按顺序跑过
请求要发出的每一个 schema——每个工具的入参 schema，以及响应格式的 schema：

```java
client.addJsonSchemaCustomizer(new InlineJsonSchemaCustomizer());
```

`InlineJsonSchemaCustomizer` 是库自带的那一个：它把每个指向 `$defs` 的 `$ref` 换成它所指定义的副本，
于是结果读起来不需要 `$defs`——这是为不接受 `$ref` 的协议准备的那一步。你自己写一个，用 lambda 就够了：

```java
client.addJsonSchemaCustomizer(schema -> /* 这个协议要的 schema */);
```

每个结果都按 schema 缓存，所以一个 schema 只重塑一次，而不是每次调用重塑一次；新增或移除 customizer
都会清掉缓存。`removeJsonSchemaCustomizer` 可以把某个撤下来，`jsonSchemaCustomizers()` 给出列表——这些
都属于配置，应当在客户端被共享之前完成。schema 被重塑过的工具从客户端出来时是一个
[`DelegatingTool`](tools.md)。

## 常驻在客户端上的配置

模型、温度或响应格式如果每次调用都共用，就该放在客户端上：

```java
ChatOptions defaults = new ChatOptions();
defaults.setModel("gpt-4o-mini");
client.setDefaultOptions(defaults);
```

默认值补上调用没有言明的东西：调用留作 `null` 的字段取用默认值；两个 extras 映射合并，调用的条目
按键胜出；请求头也是如此——常驻请求头对每次调用都生效，调用指定了同名请求头时以调用为准。
设置属于配置，应在客户端共享之前完成。

工具也可以常驻客户端——已注册的工具，以及每次调用都会询问的工具提供者：

```java
client.addDefaultTool(weather);
client.addToolProvider((c, request) -> List.of(weather));
```

## 一次往来前后的钩子

`ChatCustomizer` 在一次往来的一个或多个步骤上运行：`customizeRequest` 在请求发出之前，
`customizeResponse` 在响应回来的途中，`customizeStreamEvent` 在流的每个事件上。每个钩子拿到客户端
已经持有的那个值，并就地修改它。

```java
client.addChatCustomizer(new ChatCustomizer() {
    @Override
    public void customizeRequest(ChatClient client, ChatRequest request) {
        request.getOptions().getHeaders().put("X-Tenant", tenant);
    }
});
```

customizer 按添加顺序运行，未被重写的钩子什么都不做。跨线程共享的客户端会给每次调用一份
一致的列表，因此 customizer 自身必须可安全并发运行。

## 库自带的一个 customizer

`DefaultSystemMessageCustomizer` 是一个 `ChatCustomizer`，它会给每个自身不带系统消息的请求补上一条，
内容为构造它时用的那段文本。像其他 customizer 一样注册它：

```java
client.addChatCustomizer(new DefaultSystemMessageCustomizer("用一句话回答。"));
```

自己声明了系统消息的请求保持原样——常驻的那条只填空缺。Spring Boot starter 会从
`synapse4j.chat.system-message` 装配一个。

## 每次调用的 HTTP 设置

`ChatOptions` 为单次调用携带 HTTP 层设置，与客户端自身的设置并列：

```java
HttpOptions http = new HttpOptions();
http.setResponseTimeout(Duration.ofSeconds(30));

ChatOptions options = new ChatOptions();
options.setHttpOptions(http);
options.getHeaders().put("X-Request-Id", id);
```

`HttpOptions` 承载三项每次调用的设置：

- `responseTimeout`——等待响应开始到达的时长，是一个 `Duration`。JDK 传输层按请求应用它，只约束等待
  响应头的时间，从不读取正文。Apache 也按请求应用，且它的计时器还覆盖读取正文期间的一段静默间隔。
  RestClient 实现无法应用它——`RestClient` 抽象没有按请求超时——因而忽略它，并以一条警告说明一次，
  指向请求工厂自己的读取超时。不设置就不启计时器：底层客户端自身的配置说了算。
- `bodyWriteMode`——写出的正文如何到达传输层，是一个字符串，命名 `BodyWriteMode` 枚举所定义的模式之一。
  `"auto"` 是默认值，让每个传输层挑选自己最擅长的模式——JDK 传输层会把正文聚集起来，而 Apache 和
  RestClient 则流式发送。`"streamed"` 在正文产生的过程中写出它，不占内存；JDK 传输层无法以这种方式
  接收正文，会拒绝该请求。`"buffered"` 在发送前把正文聚集到内存中。若拼写不对应库定义的任何模式，则
  直接拒绝。
- `maxFrameBytes`——一个 server-sent event 帧最多可累积的字节数，按传输线上的 UTF-8 字节计。该上限对每个传输层
  都成立，超过它的帧会让读取失败，而不是把它截断。

`HttpOptions.defaults()` 返回 `"auto"`、没有自己的响应超时，以及 256 KiB 的帧预算。该类的 Javadoc
承载其余内容。

每个实现也各自携带自己的 `HttpOptions`，作为构造器的第二个参数传入——`new JdkHttpClient(delegate,
options)` 及其他 HTTP 模块中的对应构造器，其中第一个参数是该库自己用来发送的客户端（对 `JdkHttpClient`
来说是 `java.net.http.HttpClient`）。无参构造器用 `HttpOptions.defaults()` 构建一个默认的底层客户端，
因此 `new JdkHttpClient()` 就是未配置的情形。请求的设置与实现自身的设置按与调用选项相同的填空规则合并：
请求未设置的字段取实现的值，实现从未设置过的则回退到 `HttpOptions.defaults()`。
