# 工具

[English](../en/tools.md) | **中文**

一个工具有两半：模型看到的声明，以及模型调用它时运行的代码。本文两半都讲。

## 声明

`ToolDefinition` 是模型看到的东西：一个名字、一段描述，以及一个 `inputSchema`——一个 `JsonSchema`。
schema 由你的编解码器生成，因此它描述的正是你的编解码器能读回的 JSON。它还可以携带 `strict` 和提供商
特有的字段。

## 一个工具

`Tool` 接口有 `definition()` 和 `execute(arguments, context)`，外加 `name()`，默认取声明上的名字。
多数工具由下面四个类之一构建；还有第五条路，是从你自己带注解的方法上把它们读出来。

**`FunctionTool`**——一个带类型的 lambda。模型给出的入参会解码成你的类型，lambda 运行，结果再渲染回去：

```java
record Weather(String city) {}

Tool weather = FunctionTool.of(
        "get_weather",
        "查询某个城市的当前天气",
        Weather.class,
        (input, context) -> "天气晴朗：" + input.city(),
        codec);
```

输入类型必须描述一个对象，因为协议的入参就是一个对象。

**`MethodTool`**——一个现成的 Java 方法。它的签名成为声明：模型提供的每个参数成为入参 schema 里的一个
属性。类型为 `ChatContext` 的参数由对话填充，不会发给模型。把方法变成 `MethodTool` 的是 `MethodTools`
——在这里给它起名，或者交给下面那些注解去读：

```java
Tool weather = new MethodTools(codec).of("get_weather", "查询天气", method, service);
```

**`ManualTool`**——只有声明。自己运行模型的调用时用它：它携带声明，并拒绝执行。

**`DelegatingTool`**——替身工具：它给出自己的声明，同时原封不动地运行被代理的那个工具。
`DelegatingTool.withInputSchema(delegate, inputSchema)` 呈现的是被代理者的声明，只是换掉了入参 schema；
客户端重塑过 schema 的工具，出来时就是它：

```java
Tool strictWeather = DelegatingTool.withInputSchema(weather, stricterSchema);
```

## 用自己的方法声明工具

`@ToolMethod` 和 `@ToolParam` 把声明放到方法本身上，`MethodTools` 再把一个类读成工具：

```java
public class WeatherService {

    @ToolMethod(name = "get_weather", description = "查询某个城市的当前天气")
    public String weather(@ToolParam(name = "city") String city) {
        ...
    }
}

List<Tool> tools = new MethodTools(codec).from(service);
```

`from(bean)` 读出对象上所有带注解的方法——需要它自己来跑的实例方法，以及不需要实例的静态方法；而
`from(WeatherService.class)` 只读静态方法，那是类唯一能提供的。`from(WeatherService.class, bean)` 则从给定
的类上读出这些方法，并在给定的实例上运行——容器把 bean 包成代理时，工具照样读得出来，而调用仍落在代理上。
可见性不影响结果，方法放在哪一层也不
影响：protected 和 private 的照样读，类只是继承来的那些也读，覆盖方法则顶替它所覆盖的那个。两个方法
如果会解析出同一个工具名，就直接拒绝，不会留到后面才撞上。

每个属性都可选，没写的就不动：

- `@ToolMethod.name` 留空就用方法名，`description` 留空就没有描述，`type` 留空就用读取时默认的实现。
- `@ToolParam.name` 沿用编译器为它记下的名字——值得在构建里开 `-parameters`，不开的话编译器什么都不记，
  入参 schema 里就会冒出叫 `arg0` 的属性。`description` 留空就是没有描述。
- `required` 和 `fromModel` 由参数自身判断：编解码器认为可选的值（比如 `Optional`）不是必填的，
  `ChatContext` 类型的参数由对话填充，而不是由模型产出。写上其中之一即可覆盖这一判断。
- `schema`（在参数上或在方法上）是用来替代按类型推导出的那份 schema——替换单个属性的，或替换整个外层结构
  的。它就是编解码器必须绑定的 schema，因此这是契约，不是提示。
- `strict` 是提供商侧的开关，要求强制执行 schema，而不只是尽量照它来。留空就不发，这和“不要求强制
  执行”不是一回事：协议自身的默认行为说了算。

属性承载的是文本，不是决定：来自配置的名字或描述通过 `ToolMethodSpecCustomizer` 送进来，它在注解之后、
工具建好之前，对每个方法的解析跑一遍：

```java
new MethodTools(codec)
        .addCustomizer(spec -> spec.getParameters().get(0).setName(nameFromConfig))
        .from(service);
```

已经持有值（而非文本）的 customizer 直接写进去——参数的 `resolvedSchema`、工具的 `resolvedSchema`、
它的 `extras`——省去一趟经由文档的往返。模型不产出的参数也这样拿到值：把参数标为不是模型的
（`fromModel`），再写上它的 `valueProvider`——一个 `ToolParameterValueProvider`，即一个函数，入参是
工具的解析结果、参数自己的条目，以及调用携带的 `ChatContext`（调用不携带时为 `null`），返回要传入的
值。来自配置的值无需继承任何东西就能填上：

```java
new MethodTools(codec)
        .addCustomizer(spec -> spec.getParameters().forEach(p -> {
            if (p.getParameter().getType() == Locale.class) {
                p.setFromModel("false");
                p.setValueProvider((tool, parameter, context) -> configuredLocale);
            }
        }))
        .from(service);
```

`ChatContext` 参数则无需如此——留空时它回退到的就是对话本身。

`@ToolMethod.type` 指定用哪个类来构建工具，用于内置实现覆盖不到的情况。默认情况下，这个名字是全限定
类名：类会被加载，必须实现 `Tool`，并且必须声明一个接收方法解析结果和你的编解码器的构造器——形状是
`(ToolMethodSpec, JsonCodec)`，`MethodTool` 本身就符合。该构造器返回什么就是什么，直接当作工具使用：
之后没有任何步骤来补完或调整它，所以用这种方式指名的类交回来的是一个可以直接运行的工具。

想按自己的方式解析这些名字——比如拿到容器管理的实例——就在 `MethodTools` 读取器上用
`specToolFactory(...)` 把默认实现换成一个自己的工厂。工厂接收方法的完整解析结果和编解码器，返回成品
工具：

```java
List<Tool> tools = new MethodTools(codec)
        .specToolFactory((spec, c) -> spec.getType().isBlank() ? new MethodTool(spec, c)
                : toolBeans.get(spec.getType()))
        .from(service);
```

Spring Boot 应用不必自己调用 `from` 也能走这条路：`@Tools` 标在类上，组件扫描把它注册成 bean，
starter 在启动时把它的工具放到聊天客户端上——见 [Spring Boot](spring-boot.md#工具)。

## 运行调用

裸客户端把响应里的工具调用交给你，由你自己驱动。想让库来运行它们，就包装客户端：

```java
ChatClient client = new ToolCallingChatClient(new OpenAiCompletionsChatClient(http, codec, config));
```

`ToolCallingChatClient` 运行模型的各轮工具调用，直到模型不再要求，并在此过程中把调用和结果追加进请求。
最后一个答案留给你去 `continueWith`。同一个循环也跑在 `stream` 上——见
[流式](streaming.md#带工具的流式)。

## 执行策略

循环把每一轮的调用都交给一个 `ToolExecutor`。默认的 `DefaultToolExecutor` 按名字解析每次调用，按顺序
内联运行整批，对失败的调用则以标记为错误的文本作答：

```java
ToolExecutor executor = new DefaultToolExecutor();
ChatClient client = new ToolCallingChatClient(inner, executor);
```

除了无参构造器，双参数构造器同时接收 `ExecutorService` 和 `ErrorHandler`；三参数构造器再加上
`maxTurns` 上限。这个服务归你所有——执行器不会关闭它——所以在哪里打开就在哪里关闭：

```java
try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
    ToolExecutor executor = new DefaultToolExecutor(workers, ErrorHandlers.rethrow(), 5);
    ChatClient client = new ToolCallingChatClient(inner, executor);
}
```

- 一个 `ExecutorService`——并发运行整批，而不是内联，结果仍按调用顺序组装；
- 一个 `ErrorHandler`——失败的调用去哪里：作为模型可以据此重试的文本交回，还是抛给调用方。现成的有
  `ErrorHandlers.message(prefix)`、`.fixed(text)` 和 `.rethrow()`。协议没有承载失败信息的成员时
  ——两个 OpenAI 协议都是如此——这段文本就是模型唯一能看到的信号，因此 `.fixed(text)` 和自己写的
  处理器都应当在文本里说明调用失败了；`.message(prefix)` 已经这么做；
- 一个 `maxTurns` 上限——限制循环最多跑多久。一旦本轮跑到该轮数上限，就整体拒绝这一批：其中没有一个
  调用会运行，也不会追加任何内容，循环就地结束。此时 `chat` 返回的是那个要求发起调用的响应，它的工具
  调用仍留在消息里，未获回答——这同时也是信号：因模型结束而收尾的一轮根本不会要求调用。自己驱动剩下的
  调用，就从那个响应接着往下走。

调用了不存在的工具会以 `ToolNotFoundException` 失败，并和其他失败走同一条路径。

## 在客户端上注册工具

工具可以放在请求上，也可以常驻客户端、合并进每个请求：

```java
client.addDefaultTool(weather);
```

`ToolProvider` 是动态的对应物：每次调用都会问它，因此工具集合可以在客户端背后变化。向它发问时会带上
发这次调用的那个客户端——注册在多个客户端上的一个提供者因此能区分它们——以及即将发出的请求，其
上下文指明了对话：

```java
client.addToolProvider((asking, request) -> request.getContext() != null
        && "admin".equals(request.getContext().getSessionId())
        ? List.of(weather, adminTools)
        : List.of(weather));
```

## 让模型自己选

`ChatOptions.toolChoice` 说明模型是否可以调用工具——`auto`、`none`、`required`，或者指定某一个工具：

```java
ChatOptions options = new ChatOptions();
options.setToolChoice(ChatOptions.TOOL_CHOICE_REQUIRED);

// 或者恰好指定一个工具，不能是别的：
options.setToolChoice(ChatOptions.TOOL_CHOICE_TOOL);
options.setToolChoiceName("get_weather");
```
