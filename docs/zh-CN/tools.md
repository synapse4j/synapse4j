# 工具

[English](../en/tools.md) | **中文**

一个工具有两半：模型看到的声明，以及模型调用它时运行的代码。本文两半都讲。

## 声明

`ToolDefinition` 是模型看到的东西：名字、描述，以及作为 JSON Schema 文本的 `inputSchema`。schema
由你的编解码器生成，因此它描述的正是你的编解码器能读回的 JSON。它还可以携带 `strict` 和提供商特有的
字段。

## 一个工具

`Tool` 接口有 `definition()` 和 `execute(arguments, context)`，外加 `name()`，它默认取声明上的名字。
多数工具由下面三个类之一构建。

**`FunctionTool`**——一个带类型的 lambda。模型的参数被解码成你的类型，lambda 运行，返回值再被渲染
回去：

```java
record Weather(String city) {}

Tool weather = FunctionTool.of(
        "get_weather",
        "查询某个城市的当前天气",
        Weather.class,
        (input, context) -> "巴黎天气晴朗",
        codec);
```

输入类型必须描述一个对象，因为协议的参数就是一个对象。

**`MethodTool`**——一个现成的 Java 方法。它的签名成为声明：模型提供的每个参数成为参数 schema 里的
一个必填属性。类型为 `ChatContext` 的参数由对话填充，不会发给模型：

```java
Tool weather = MethodTool.of("get_weather", "查询天气", method, service, codec);
```

**`ManualTool`**——只有声明。当你要自己运行模型的调用时用它：它携带声明，并拒绝执行。

## 运行调用

裸客户端把响应里的工具调用交给你，由你驱动。想让库来运行，就包装客户端：

```java
ChatClient client = new ToolCallingChatClient(new OpenAiCompletionsChatClient(http, codec, config));
```

`ToolCallingChatClient` 跑完模型发起的各轮工具调用，直到模型不再要求调用，并在此过程中把调用与结果
追加进请求。最后一个答案留给你去 `continueWith`。

## 执行策略

循环把每一轮的调用交给一个 `ToolExecutor`。默认的 `DefaultToolExecutor` 按名字解析每次调用，按顺序
在调用线程上内联运行整批，并把失败的调用以标记为错误的文本作答：

```java
ToolExecutor executor = new DefaultToolExecutor();
ChatClient client = new ToolCallingChatClient(inner, executor);
```

除了无参构造器，还有接收 `ExecutorService` 与 `ErrorHandler` 的双参数构造器，以及再加上
`maxTurns` 上限的三参数构造器：

```java
ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
ToolExecutor executor = new DefaultToolExecutor(workers, ErrorHandlers.rethrow(), 5);
```

- 一个 `ExecutorService`——并发运行整批，而不是内联，结果仍按调用顺序组装；
- 一个 `ErrorHandler`——失败的调用去哪里：作为模型可以据此重试的文本交回，还是抛给调用方。现成的有
  `ErrorHandlers.message(prefix)`、`.fixed(text)` 和 `.rethrow()`。协议没有失败标志时（两个 OpenAI
  协议都是），这段文本是模型唯一能看到的信号，因此 `.fixed(text)` 和自己写的 handler 应当在文本里说明
  调用失败了；`.message(prefix)` 已经这么做；
- 一个 `maxTurns` 上限——一轮跑够那么久之后拒绝这一批，循环就地结束。

调用了不存在的工具会以 `ToolNotFoundException` 失败，并和其他失败走同一条路径。

## 在客户端上注册工具

工具可以放在请求上，也可以常驻客户端、并入每个请求：

```java
client.addDefaultTool(weather);
```

`ToolProvider` 是动态的对应物：每次调用都会问它，因此工具集合可以在客户端背后变化：

```java
client.addToolProvider((c, request) -> List.of(weather));
```

## 让模型自己选

`ChatOptions.toolChoice` 说明模型是否可以调用工具——`auto`、`none`、`required`，或者 `tool` 表示
一个指定工具：

```java
ChatOptions options = new ChatOptions();
options.setToolChoice(ChatOptions.TOOL_CHOICE_REQUIRED);
```
