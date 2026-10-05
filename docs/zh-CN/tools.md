# 工具

[English](../en/tools.md) | **中文**

一个工具有两半：模型看到的声明，以及模型调用它时运行的代码。本文两半都讲。

## 声明

`ToolDefinition` 是模型看到的东西：名字、描述，以及一个 `inputSchema`——一个 `JsonSchema`。schema
由你的编解码器生成，因此它描述的正是你的编解码器能读回的 JSON。它还可以携带 `strict` 和提供商特有的
字段。

## 一个工具

`Tool` 接口有 `definition()` 和 `execute(arguments, context)`，外加 `name()`，它默认取声明上的名字。
多数工具由下面三个类之一构建；还有一条路，是从你自己带注解的方法上把它们读出来。

**`FunctionTool`**——一个带类型的 lambda。模型的参数被解码成你的类型，lambda 运行，再把返回值渲染
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

**`MethodTool`**——一个现成的 Java 方法。它的签名成为声明：模型提供的每个参数成为参数 schema 里的一个
属性。类型为 `ChatContext` 的参数由对话填充，不会发给模型。把方法变成 `MethodTool` 的是 `MethodTools`
——在这里给它起名，或者交给下面那些注解去读：

```java
Tool weather = new MethodTools(codec).of("get_weather", "查询天气", method, service);
```

**`ManualTool`**——只有声明。当你要自己运行模型的调用时用它：它携带声明，并拒绝执行。

## 用自己的方法声明工具

`@ToolMethod` 和 `@ToolParam` 让声明就长在方法上，`MethodTools` 负责把一个类读成工具：

```java
public class WeatherService {

    @ToolMethod(name = "get_weather", description = "查询某个城市的当前天气")
    public String weather(@ToolParam(name = "city") String city) {
        ...
    }
}

List<Tool> tools = new MethodTools(codec).from(service);
```

`from(bean)` 读出这个对象上所有带注解的方法——需要它自己来跑的实例方法，以及不需要实例的静态方法；
`from(WeatherService.class)` 只读静态方法，那是类唯一能提供的。可见性不影响结果，声明在哪一层也不
影响：protected、private 的方法照样读，只从父类或接口 default 方法继承来的也读，覆盖方法顶替它
覆盖掉的那个。两个方法最终解析出同一个工具名，会被直接拒绝，不会留到后面才撞上。

注解项都是可选的，没写的就不动：

- `@ToolMethod.name` 留空就用方法名，`description` 留空就是没有描述，`type` 留空就用读取时默认的实现。
- `@ToolParam.name` 沿用编译器记下的参数名——值得在构建里开 `-parameters`，不开的话编译器什么都不记，
  参数 schema 里就会冒出叫 `arg0` 的属性。`description` 留空就是没有描述。
- `required` 和 `fromModel` 由参数自己判断：codec 认为可选的值（比如 `Optional`）不是必填的，
  `ChatContext` 类型的参数由对话填充、不由模型产出。写了其中一个，就以写的为准。
- `schema`（参数上或方法上）用来替代按类型推导出来的那份 schema——替换单个属性的，或者替换整个信封的。
  它就是 codec 必须绑得住的 schema，所以这是契约，不是建议。
- `strict` 是发给提供商的那个开关，要求它强制执行 schema，而不只是尽量照它来。留空就不发，
  这和"要求它别强制"是两回事：协议的默认行为说了算。

注解承载的是文本，不是决定。来自配置的名字或描述由 `ToolMethodSpecCustomizer` 送进来，它在注解之后、
工具被建出来之前，对每个方法的解析跑一遍：

```java
new MethodTools(codec)
        .addCustomizer(spec -> spec.getParameters().get(0).setName(nameFromConfig))
        .from(service);
```

手上已经有值（而不是文本）的 customizer 直接写进去就行——参数的 `resolvedSchema`、工具的
`resolvedSchema`、`extras`——省掉一趟"序列化成文档再读回来"。模型不产出的参数也是这样拿到值的：
给它写上 `valueProvider`，不必为此继承。`ChatContext` 参数不用写——留空时它回退到的就是对话本身。

`@ToolMethod.type` 指定由哪个类来建这个工具，用于内置实现覆盖不到的情况。留空就是 `MethodTool`，
`specToolFactory(...)` 可以换掉默认实现，于是这个名字的含义由你的应用说了算。工厂拿到的是整份解析结果
和 codec，交回来的就是成品：`MethodTool(ToolMethodSpec, JsonCodec)` 是一个类被指名时所要提供的东西，
之后没有别的步骤再来补完它。

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
`maxTurns` 上限的三参数构造器。这个服务归你所有——执行器不会关闭它——所以在打开它的地方关闭它：

```java
try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
    ToolExecutor executor = new DefaultToolExecutor(workers, ErrorHandlers.rethrow(), 5);
    ChatClient client = new ToolCallingChatClient(inner, executor);
}
```

- 一个 `ExecutorService`——并发运行整批，而不是内联，结果仍按调用顺序组装；
- 一个 `ErrorHandler`——失败的调用去哪里：作为模型可以据此重试的文本交回，还是抛给调用方。现成的有
  `ErrorHandlers.message(prefix)`、`.fixed(text)` 和 `.rethrow()`。协议没有失败标志时（两个 OpenAI
  协议都是），这段文本是模型唯一能看到的信号，因此 `.fixed(text)` 和自己写的处理器都应当在文本里说明
  调用失败了；`.message(prefix)` 已经这么做；
- 一个 `maxTurns` 上限——一轮跑够那么久之后拒绝这一批，循环就地结束。

调用了不存在的工具会以 `ToolNotFoundException` 失败，并和其他失败走同一条路径。

## 在客户端上注册工具

工具可以放在请求上，也可以常驻客户端、并入每个请求：

```java
client.addDefaultTool(weather);
```

`ToolProvider` 则是动态的：每次调用都会问它，因此工具集合可以在客户端背后变化：

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
