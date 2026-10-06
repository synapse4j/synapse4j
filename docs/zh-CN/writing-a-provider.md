# 编写一个提供商模块

[English](../en/writing-a-provider.md) | **中文**

一个提供商模块把共享模型翻译成某个协议，再翻译回来。本文说明它的形状。

## 提供商是什么

一个模块里有两部分：

- 一个**协议模型**——协议自己的 JSON，按 token 写出、按 token 读入；
- 一个**适配器**——把共享的 `ChatRequest` 与 `ChatResponse` 映射到那个协议模型、再映射回来的
  客户端。

协议模型从不漏进 core：它位于提供商的包中，只有共享类型跨过边界。落到代码里，它就是按 token
写出与读入的那些方法——再加上流式用到的逐帧事件类型常量——而不是一套类层次结构：下面的例子把
共享请求直接写进一个 `JsonWriter`，再从 `JsonReader` 上直接读回答案。

## 客户端

继承 `AbstractChatClient`。它在一次往来的前后运行 customizer 和客户端自身的默认值；子类提供往来
本身：

- `doChat(ChatRequest)`——发出请求，读回答案；
- `doStream(ChatRequest)`——打开一个 `ChatStream`。

下面所有内容都属于一个小类 `MyChatClient`，旁边放的就是它所在模块的配置。基类只要求两个方法——
`doChat` 和 `doStream`；它的构造函数不接收参数，customizer、默认选项和工具在构造之后通过它的
方法挂上去：

```java
public final class MyChatClient extends AbstractChatClient {

    private static final String ENDPOINT = "/v1/chat"; // 本协议所在的路径

    private final HttpClient http;
    private final JsonCodec codec;
    private final MyConfig config;

    public MyChatClient(HttpClient http, JsonCodec codec, MyConfig config) {
        this.http = http;
        this.codec = codec;
        this.config = config;
    }
}
```

配置是这个模块自己的一个普通类——真正的配置 `OpenAiConfig` 和 `AnthropicConfig` 不继承任何
东西——至少带上 base URL 和 API key：

```java
public class MyConfig {

    private String baseUrl = "https://api.example.com";
    private @Nullable String apiKey;

    // getter 与 setter；真正的配置是 Lombok 的 @Data 类
}
```

这些例子把请求 URL 拼成 `config.getBaseUrl() + ENDPOINT`。当同一家厂商有第二种协议时，该提升
为钩子的正是这段路径——OpenAI 的抽象基类把它做成一个抽象方法 `endpoint()`，由每种协议各自
作答。

任一钩子运行之前，基类已经把请求准备好：它先应用客户端自身的默认值，再依次把每一个已注册的请求
customizer 就地应用到调用者传进来的那个实例上，然后把准备好的请求交给 `doChat` 或 `doStream`。
子类绝不自己调用 `prepare`——再跑一遍会把 customizer 应用两次。对于钩子构建的流，
基类期望每个事件都从 `eventPipeline()` 穿过——把流事件 customizer 收在一个
`Consumer<ChatStreamEvent>` 里——在它的源头与折叠之间：把它交给你的 `DefaultChatStream`，它就
在那里运行，而不在你的迭代器里。

在服务端保存对话的协议——例如 OpenAI Responses API——还要多改一处：客户端覆盖 `continueWith`，
记录它下一次调用需要的东西，而不是把完整的对话记录归档。见[服务端对话](conversations.md#服务端对话)。

当同一家厂商的两种协议共享传输流程时——请求头、状态处理、拒绝路径——把它抽到一个抽象基类里，
`AbstractOpenAiChatClient` 就是这么做的，把端点、文档和答案的形状留作钩子。

## 发送

两个钩子发送的是同一个请求——只有文档不同——所以请求的构建是类里共用的一个辅助方法。构建库
自己的 `HttpRequest`（不是 JDK 的），设置它的方法、请求头、正文和本次调用的 `HttpOptions`：

```java
HttpRequest buildRequest(ChatRequest request, boolean streaming) {
    HttpRequest httpRequest = new HttpRequest(config.getBaseUrl() + ENDPOINT);
    httpRequest.setMethod(HttpRequest.POST);
    httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
    if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
        httpRequest.getHeaders().put("Authorization", List.of("Bearer " + config.getApiKey()));
    }
    // 最后应用，这样调用者的请求头会胜过模块自己的任何请求头
    request.getOptions().getHeaders().forEach((name, value) -> httpRequest.getHeaders()
            .put(name, List.of(value)));
    httpRequest.setOptions(request.getOptions().getHttpOptions());
    httpRequest.setBody(out -> {
        try (JsonWriter writer = codec.writer(out)) {
            write(request, writer, streaming);
        }
    });
    return httpRequest;
}
```

然后发送就是一次调用——把构建好的请求交给 `HttpClient`：

```java
try (HttpResponse response = http.send(buildRequest(request, false))) {
    // 在碰正文之前先读状态
}
```

本次调用自己的 `HttpOptions`——它的每次调用的 HTTP 设置：响应超时、正文写出模式、SSE 帧预算——
随请求传到传输层；没设置时它们是 `null`，由传输层自己的默认值生效。见
[每次调用的 HTTP 设置](customizing.md#每次调用的-http-设置)。认证只是一个普通的请求头，和其他请求头一样：
配置里设了 API key 时，模块用它设置该请求头——没设置或为空时，不发任何认证请求头，那些不需要
认证就能使用这个协议的服务器正依赖这一点。请求头名字与方案是协议自己的（OpenAI 用
`Authorization` 承载一个 `Bearer` token，Anthropic 用 `x-api-key`）。调用自己的请求头排在最后，
来自 `request.getOptions().getHeaders()`：拷进同一个请求头 map，于是调用者的条目替换掉模块的
条目并胜出。

正文是一个 `HttpBody`：一个在传输层索要时才写出的 lambda，因此文档是按需产生的，而不是先搭成
一棵树。这些字节是边产生边发还是先攒起来，由传输层决定，受本次调用的 `bodyWriteMode` 支配——
默认值 `"auto"` 让每个传输层挑选自己最擅长的模式：JDK 传输层会把写出的正文全部攒起来，拒绝
`"streamed"`，而 Apache 和 RestClient 会流式发出。第二次写出会产生同样的字节，因为传输层可能
在重试或重定向时再写一次。各模式见
[每次调用的 HTTP 设置](customizing.md#每次调用的-http-设置)。

先读状态再碰正文——正文是流，只能读一次。非 2xx 的答案是拒绝：读出它的细节，抛一个
`SynapseHttpException`。`http.send` 本身以非受检方式作答：一次从未拿到答案的调用——DNS、
连接、TLS、读超时——以 `SynapseException` 的形式到来。上面那段里的受检 `IOException` 是
try-with-resources 里响应的关闭，是阻塞式往来唯一的受检时刻；下面完整的 `doChat` 展示了它在
哪里被包装。把上述步骤与文档遍历、答案遍历拼接起来的完整 `doChat` 出现在
[读入答案](#读入答案)的末尾。

## 写出文档

写出协议自己的成员，可以按 token 写，也可以组装成一个 map——map 经由 `JsonWriter.writeValue`
写出，后者会把它写成对象。两条规则：

- 没有设置值的成员从不写出；
- 节点的 `ProviderExtras` 合并到你写出的成员之上，因此应用设置的字段以提供商自己的名字透传。

```java
void write(ChatRequest request, JsonWriter writer, boolean streaming) {
    ChatOptions options = request.getOptions();
    Map<String, Object> members = new LinkedHashMap<>();
    if (options.getModel() != null) {
        members.put("model", options.getModel());
    }
    // ... 这个协议建模的成员，每个只在它的值已设置时才写出 ...
    if (streaming) {
        // 这个协议用文档里的一个成员请求流式
        members.put("stream", true);
    }
    options.getExtras().mergeInto(members);
    writer.writeValue(members);
}
```

`buildRequest` 写正文用的就是这个 `write`：`doChat` 用 `false` 调用它，`doStream` 用 `true`。

## 读入答案

用 `JsonReader` 按 token 遍历正文，读进一个 `ChatResponse`。reader 建立在响应的正文流之上——与
构建请求文档时所用 writer 对应的那一端：

```java
try (JsonReader reader = codec.reader(response.getBody())) {
    ChatResponse answer = readAnswer(reader);
}
```

遍历是一个字段名循环：`nextToken()` 推进 reader，`name()` 取出每个成员名，成员名之后的那个 token
承载它的值。你建模的成员就从那个 token 上读出；你没建模的成员会被保留而不是丢掉——
`captureValue()` 把任意形状的值读成解码后的形式（一个 map、一个 list、一个字符串、一个数字、
一个布尔值，或 null），并以它到来时的名字交给它来自的那个节点的 extras：

```java
ChatResponse readAnswer(JsonReader reader) {
    if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
        throw new SynapseException("答案不是一个 JSON 对象");
    }
    ChatResponse response = new ChatResponse();
    while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
        String field = reader.name();
        reader.nextToken();
        switch (field) {
            case "id" -> response.setId(reader.string());
            // ... 这个协议建模的成员，每个都从当前 token 上读出 ...
            default -> response.getExtras().put(field, reader.captureValue());
        }
    }
    return response;
}
```

那个 `default` 分支就是透传规则，而且它逐节点成立：`captureValue()` 把未建模的字段留在它来自的
那个节点上，当那个节点再次写出时，它的 extras 合并到 writer 写出的成员之上。对一条消息来说这是
真正的往返：`continueWith` 把模型回答的那条消息归档进对话（见[对话](conversations.md)），下一次调用
的文档遍历再把这条消息的 extras 合并进它的条目——与请求选项自己的 extras 在
[写出文档](#写出文档)里得到的合并相同。留在响应自身上的字段哪儿也去不了：响应从不发送，
没有任何东西把它的 extras 复制出去，只能由调用者自己读取。

本页的这些片段合起来就是一次阻塞式往来。`doChat` 收到的请求已经过了 `prepare`——
`AbstractChatClient` 在子类看到请求之前运行默认值与请求 customizer——它返回的 `ChatResponse`
就是调用者的答案：

```java
@Override
protected ChatResponse doChat(ChatRequest request) {
    HttpRequest httpRequest = buildRequest(request, false);

    try (HttpResponse response = http.send(httpRequest)) {
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw new SynapseHttpException(readBody(response), status);
        }
        try (JsonReader reader = codec.reader(response.getBody())) {
            return readAnswer(reader);
        }
    } catch (IOException e) {
        throw new SynapseException("无法读取答案", e);
    }
}
```

`write` 是上一节的文档遍历，`readAnswer` 是上文所述的按 token 遍历；`readBody` 读取拒绝时的
正文。往来以非受检方式作答——一次让调用拿不到答案的失败就是 `SynapseException`，而不是调用者必须
事先计划的受检条件。

## 流式

往来中流式的那一半与阻塞的一半对称。文档用协议自己的成员请求流式；然后响应以
`sseEventStream()` 作答，帧已经替你切好——每帧一个 `SseEvent`，从 `text/event-stream` 正文
解析而来；当答案根本不是事件流时为 `null`。你的迭代器把每一帧映射成一个 `ChatStreamEvent`
（`ChatStreamEvent` 是流给出的元素类型，不是 `SseEvent`），再把流交给 `DefaultChatStream`：

```java
@Override
protected ChatStream doStream(ChatRequest request) {
    HttpResponse response = http.send(buildRequest(request, true));
    try {
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw new SynapseHttpException(readBody(response), status);
        }
        SseEventStream frames = response.sseEventStream();
        if (frames == null) {
            throw new SynapseException(ENDPOINT + " 对流式请求作出了没有事件流的回答");
        }
        // 成功时响应保持打开：流拥有它，关闭流就是取消一个仍在途中的答案。
        return new DefaultChatStream(events(frames), eventPipeline(), MyChatClient::fold, response::close);
    } catch (RuntimeException failure) {
        // 从响应到达到流接管它之间，没有别的东西持有这个连接：在失败离开前释放它。
        try (HttpResponse closing = response) {
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        throw failure;
    }
}
```

`fold` 就是把单个事件合并进正在组装的答案——它是静态的，因此可以当作方法引用：

```java
/** 把单个事件合并进正在组装的答案。 */
private static void fold(ChatResponse answer, ChatStreamEvent event) {
    if (event.getId() != null) {
        answer.setId(event.getId());
    }
    if (event.getFinishReason() != null) {
        answer.setFinishReason(event.getFinishReason());
    }
    answer.getExtras().putAll(event.getExtras());
    if (event.getDelta() != null) {
        // 把 delta 的各个部分追加到答案的消息上，一次处理一种部分类型
    }
}
```

这个钩子以非受检方式作答，与 `doChat` 一样：`http.send` 没有受检失败，而响应的关闭——唯一的
受检时刻——归关闭动作所有，它的失败由 `DefaultChatStream` 替你包装好。从响应到达到流接管之间，
这里只有拒绝正文和 `sseEventStream()` 调用在跑，所以这个 `catch` 会释放连接，否则失败会让它一直被占着的。

迭代器的职责是帧到事件的映射。`SseEventStream` 是一个 `Iterator<SseEvent>`，它的 `hasNext()`
会为下一帧阻塞，所以映射是对它的惰性遍历；每个 `SseEvent` 带着该帧的 `event:` 名字（或
`null`）以及拼接好的 `data:` 行：

```java
private Iterator<ChatStreamEvent> events(SseEventStream frames) {
    return new Iterator<>() {
        public boolean hasNext() {
            return frames.hasNext();
        }

        public ChatStreamEvent next() {
            return toEvent(frames.next());
        }
    };
}

/** 把一帧映射成它的事件。 */
private ChatStreamEvent toEvent(SseEvent frame) {
    ChatStreamEvent event = new ChatStreamEvent();
    // 当协议改为在负载内部给事件命名时为 null
    event.setEventType(frame.getEvent());
    try (JsonReader reader = codec.reader(new ByteArrayInputStream(frame.getData().getBytes(StandardCharsets.UTF_8)))) {
        // ... 与 readAnswer 相同的字段名循环，读进事件的 delta 与 extras ...
    } catch (IOException e) {
        throw new SynapseException("无法读取一个流帧", e);
    }
    return event;
}
```

负载的遍历与 `readAnswer` 是同一个字段名循环：你建模的成员成为事件的 `delta`，未建模的负载
成员作为解析后的值落进 `event.getExtras()`，绝不是原始文本。有些协议把 SSE 的 `event:` 名字
留空，改为在负载内部作区分——例如 OpenAI chat completions 用 `object` 成员给每个 chunk 命名；
在那里读到的类型就是 `setEventType` 收到的值，保持原样。

`DefaultChatStream` 从你的迭代器拉取，每来一个事件就运行 `eventPipeline()`，然后再执行折叠——
即把单个事件合并进聚合答案的 `BiConsumer<ChatResponse, ChatStreamEvent>`（上文中的
`MyChatClient::fold`）——于是流事件 customizer 在你的源头与你的折叠之间运行，而两边都不需要
调用它们。最后一个构造函数参数是关闭动作，这里是 `response::close`：它只运行一次，无论流是被
关闭、耗尽还是失败，它就是取消连接的那个动作。

没有归一化内容的事件把 `delta` 留作 null，负载留在 `extras` 里。

## 失败要显眼

一次调用要求、而协议没有对应成员的东西里，只有一种会让调用失败：协议无法兑现的、对答案形状的
要求。一个违反要求却看起来像成功的答案，在调用者看来就是模型无视了指令——这是代价最高的错误
——所以模块宁可拒绝这次调用，也不让它通过。协议没有对应成员的其他一切——一个开关、一个字段、
一种模式——都不发出去，调用带着协议能承载的部分继续进行。若连这些也拒绝，一换提供商，同一段
应用代码立刻就会坏，而这正是共享模型要避免的事。

## 把提供商的拼写作为配置

当某个协议对通用字段的命名与其同类不同时——`max_completion_tokens` 对 `max_tokens`，
`reasoning` 对 `reasoning_content`——那个名字是模块配置上的一个字段，读和写共用，默认值适合
该提供商。绝不根据某次响应恰好含有什么来推断它：一段读了某个拼写却写另一个拼写的对话，会把
端点从未发出过的成员改名。
