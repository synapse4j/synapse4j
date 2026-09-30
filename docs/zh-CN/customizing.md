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

## 常驻在客户端上的配置

模型、温度或响应格式如果每次调用都共用，应该放在客户端上：

```java
ChatOptions defaults = new ChatOptions();
defaults.setModel("gpt-4o-mini");
client.setDefaultOptions(defaults);
```

默认值补上调用没有声明的东西：调用留作 `null` 的字段取默认值，两个 extras 袋子合并，调用的条目按键
胜出。设置属于配置，应在客户端被共享之前完成。

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

## 每次调用的 HTTP 设置

`ChatOptions` 为单次调用携带 HTTP 层设置，与客户端自身的设置并列：

```java
HttpOptions http = new HttpOptions();
http.setResponseTimeout(Duration.ofSeconds(30));

ChatOptions options = new ChatOptions();
options.setHttpOptions(http);
options.getHeaders().put("X-Request-Id", id);
```

`HttpOptions` 有三个旋钮：

| 字段 | 设置什么 |
|---|---|
| `responseTimeout` | 等待响应头多久；它不限制读取正文 |
| `bodyWriteMode` | `STREAMED`（默认）或 `BUFFERED`——写出的正文如何到达一个无法边写边收的传输层 |
| `maxFrameBytes` | 一个 server-sent event 帧最多可累积多少字节 |

请求的设置与实现自身的设置合并，方式和调用选项一样。
