# 定制

[English](../en/customizing.md) | **中文**

这个库是用来组装和调整的，而不是整套照搬。本文说明有哪些调整点。

## 更换 JSON 库或 HTTP 客户端

两者都是 core 里的接口，实现在各自的模块里。想用另一个，就实例化它并交给客户端：

```java
JsonCodec codec = new JacksonJsonCodec();
HttpClient http = new RestClientHttpClient(restClient);   // 或 JdkHttpClient、ApacheHttpClient
ChatClient client = new OpenAiCompletionsChatClient(http, codec, config);
```

提供商模块和你的代码都不用改。自己写一个实现，参考 `JsonCodec` 与 `HttpClient` 上的 Javadoc。

## 生成的 schema

codec 为工具的入参和结构化回答生成 JSON Schema，来源是 Java 类型，以及绑定 JSON 用的同一个
`JsonMapper`。要应用 Jackson 模块推荐的哪些选择，由 `JacksonSchemaSettings` 决定——每个选择对应一个
开关或一组设置，默认值就是推荐的做法。关掉某个选择，就是「不应用它」；这也正是替换它的办法：关掉，再
挂上你自己的 victools `Module`。

```java
JacksonSchemaSettings settings = new JacksonSchemaSettings();
settings.setFlattenOptionals(false);            // 不应用推荐的那个选择

SchemaGeneratorConfigBuilder builder =
        JacksonSchemaConfigBuilders.encodeSchemaConfigBuilder(mapper, settings);
builder.with(myOptionalModule);                 // 换成你自己的规则

JsonCodec codec = new JacksonJsonCodec(mapper, new SchemaGenerator(builder.build()),
        new SchemaGenerator(JacksonSchemaConfigBuilders.decodeSchemaConfigBuilder(mapper, settings).build()));
```

每个选择是什么、默认做什么，写在 `JacksonSchemaSettings` 的 Javadoc 里；每个模块贡献什么，写在它
自己的 Javadoc 里。`settings` 传 `null` 表示一个选择都不应用，留一份 victools 的朴素配置给想全部
自己组装的人。

## 常驻在客户端上的配置

模型、温度或响应格式如果每次调用都共用，应该放在客户端上：

```java
ChatOptions defaults = new ChatOptions();
defaults.setModel("gpt-4o-mini");
client.setDefaultOptions(defaults);
```

默认值补上调用没有声明的东西：调用留作 `null` 的字段取默认值，两个 extras 映射合并，调用的条目按键
胜出。设置属于配置动作，应在共享客户端之前完成。

工具也可以常驻客户端——注册的工具，以及每次调用都会问的 `ToolProvider`：

```java
client.addDefaultTool(weather);
client.addToolProvider((c, request) -> List.of(weather));
```

## 一次往来前后的钩子

`ChatCustomizer` 在一次往来的一个或多个步骤上运行：`customizeRequest` 在请求发出之前，
`customizeResponse` 在答案返回途中，`customizeStreamEvent` 在流的每个事件上。每个钩子拿到的是
客户端已经持有的那个值，并就地修改它。

```java
client.addChatCustomizer(new ChatCustomizer() {
    @Override
    public void customizeRequest(ChatClient client, ChatRequest request) {
        request.getOptions().getHeaders().put("X-Tenant", tenant);
    }
});
```

`ChatCustomizer` 按添加顺序运行，没有重写的钩子什么都不做。客户端跨线程共享时，每次调用拿到一致的
列表，因此 `ChatCustomizer` 自身必须可并发运行。

## 库自带的一个 customizer

`DefaultSystemMessageCustomizer` 是库提供的一个 `ChatCustomizer`：凡是自身没有系统消息的请求，它都补上
一条内容为构造时那段文本的系统消息。像其他 customizer 一样注册它：

```java
client.addChatCustomizer(new DefaultSystemMessageCustomizer("用一句话回答。"));
```

自带系统消息的请求保持原样——常驻的那条只填空缺。Spring Boot starter 会从
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

`HttpOptions` 承载本次调用的 HTTP 层设置：等待响应头的超时（它不限制读取正文）、写出的正文如何交给
一个无法直接接收流式正文的传输层，以及一个 server-sent event 帧的预算。字段名与取值以该类的 Javadoc
为准；其中正文写出模式是字符串 `"streamed"` 或 `"buffered"`，不是枚举。

请求的设置与实现自身的设置合并，方式和调用选项一样。
