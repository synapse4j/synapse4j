# 编写一个提供商模块

[English](../en/writing-a-provider.md) | **中文**

一个提供商模块把共享模型翻译成某个协议，再翻译回来。本文说明它的形状。

## 提供商是什么

一个模块里有两部分：

- 一个**协议模型**——协议自己的 JSON，按 token 写出、按 token 读入；
- 一个**适配器**——把共享的 `ChatRequest` 与 `ChatResponse` 映射到那个协议模型、再映射回来的客户端。

协议模型从不漏进 core：它住在提供商的包里，只有共享类型跨过边界。

## 客户端

继承 `AbstractChatClient`。它围绕一次往来运行 `ChatCustomizer` 和客户端自身的默认值；子类提供往来
本身：

- `doChat(ChatRequest)`——发出请求，读回答案；
- `doStream(ChatRequest)`——打开一个 `ChatStream`。

`AbstractChatClient` 交给你 `prepare(request)`——默认值与请求钩子，就地应用——以及
`eventPipeline()`，那是流事件钩子，准备好让每个事件在折叠前过一遍。

当同一家族的两种协议共享传输流程——请求头、状态处理、拒绝路径——就把它抽到一个抽象基类里，
`AbstractOpenAiChatClient` 就是这么做的，把端点、文档和答案的形状留作钩子。

## 发送

构建一个 `HttpRequest`（库自己的，不是 JDK 的），设置它的方法、请求头、正文和本次调用的
`HttpOptions`，交给 `HttpClient`：

```java
void send(ChatRequest request) throws IOException {
    HttpRequest httpRequest = new HttpRequest(baseUrl + endpoint);
    httpRequest.setMethod(HttpRequest.POST);
    httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
    httpRequest.setBody(out -> {
        try (JsonWriter writer = codec.writer(out)) {
            write(request, writer);
        }
    });

    try (HttpResponse response = http.send(httpRequest)) {
        // 在碰正文之前先读状态
    }
}
```

正文是一个 `HttpBody`：一个在传输层索要时才写出的 lambda，因此文档边写边发，而不是先搭成一棵树。第二
次写出会产生同样的字节，因为传输层可能在重试或重定向时再写一次。

先读状态再碰正文——正文是流，只能读一次。非 2xx 的答案是拒绝：读出它的细节，抛一个
`SynapseHttpException`。

## 写出文档

用 `JsonWriter` 按 token 写出协议自己的成员。两条规则：

- 没有设置值的成员从不写出；
- 节点的 `ProviderExtras` 合并到你写出的成员之上，因此应用设置的字段以提供商自己的名字透传。

```java
Map<String, Object> members = new LinkedHashMap<>();
if (options.getModel() != null) {
    members.put("model", options.getModel());
}
// ... 这个协议建模的成员，每个只在它的值已设置时才写出 ...
options.getExtras().mergeInto(members);
```

## 读入答案

用 `JsonReader` 按 token 遍历正文，读进一个 `ChatResponse`。你没有建模的每个字段都进入它来自的那个
节点的 extras——这正是未建模字段能往返存活的原因。

## 流式

用协议自己的成员请求流式，然后读响应的 `SseEventStream`——每帧一个 `SseEvent`——把每一帧映射成携带
协议 `eventType` 和归一化 `delta` 的 `ChatStreamEvent`。`DefaultChatStream` 把事件折叠成聚合答案；
构建它要用你的迭代器、一个把单个事件折叠进答案的 `BiConsumer<ChatResponse, ChatStreamEvent>`，以及
一个释放响应的关闭动作。

没有归一化内容的事件把 `delta` 留作 null，负载留在 `extras` 里。

## 失败要显眼

调用要求的东西里，如果协议没有对应成员，只有一种会让调用失败：协议无法兑现的、对答案形状的要求。一个
违反要求却看起来像成功的答案，在调用者看来就是模型无视了指令——这是代价最高的错误——所以模块宁可拒绝
调用，也不让它通过。协议没有对应成员的其他东西——一个开关、一个字段、一种模式——都不发出去，调用带着
协议能承载的部分继续进行。若因此拒绝，一换提供商同一段应用代码立刻就会坏，而这正是共享模型要避免的
事。

## 把提供商的拼写作为配置

当某个协议对通用字段的拼写与同类不同——`max_completion_tokens` 对 `max_tokens`，`reasoning` 对
`reasoning_content`——那个名字是模块配置上的字段，读和写共用，默认值适合该提供商。绝不根据某次响应
恰好含有什么来推断：一段读了某个拼写却写另一个拼写的对话，会把端点从未发出过的成员改名。
