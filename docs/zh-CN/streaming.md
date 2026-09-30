# 流式

[English](../en/streaming.md) | **中文**

流式调用与阻塞调用共享同一个请求，只有结果不同：`chat` 返回完整答案，`stream` 返回一个
`ChatStream`，你逐个事件拉取。

```java
try (ChatStream stream = client.stream(request)) {
    for (ChatStreamEvent event : stream) {
        if (event.getDelta() != null) {
            for (ContentPart part : event.getDelta().getParts()) {
                if (part instanceof TextPart text) {
                    System.out.print(text.getText());
                }
            }
        }
    }
}
```

## 事件

一个事件就是一个协议事件，一一对应，并按到达顺序排列。它携带协议自己的 `eventType`——你可以据此
分派——以及在有的情况下提供一个归一化的视图：一条 `delta` 消息，内含这个事件新增的内容部分；一个
`finishReason`；`usage`；以及响应的 `id` 和 `model`。

每个提供商模块把自己的事件类型声明为常量：

| 模块 | 例子 |
|---|---|
| `synapse4j-openai`（Completions） | `OpenAiEventTypes.CHUNK`、`OpenAiEventTypes.DONE` |
| `synapse4j-openai`（Responses） | `OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA`、`.COMPLETED` |
| `synapse4j-anthropic` | `AnthropicEventTypes.CONTENT_BLOCK_DELTA`、`.MESSAGE_STOP` |

没有归一化内容的事件——块的开始或结束、生命周期标记——`getDelta()` 为 null，负载留在 `getExtras()`
里。

## 组装好的答案

流把它交出的每个事件都折叠进一个聚合响应，因此驱动 UI 的同一次消费也在构建完整答案：

```java
ChatResponse response = stream.aggregatedResponse();
```

循环跑到结束后，它正是 `chat` 会返回的那个。它从不阻塞，也从不驱动消费——它报告迭代器已经折叠的
内容。

## 拉取，以及提前停止

迭代是惰性且阻塞的：`hasNext()` 等待下一个事件，这也正是对提供商的背压来源。迭代的线程就是事件到达
的线程。

一个流只能走一遍。每次调用 `iterator()` 都返回同一个迭代器，第二次调用会抛异常。关闭会释放流背后的
连接，取消仍在飞行中的响应；它是幂等的，且可从任意线程调用。跑到结尾的流会自行释放同一条连接，因此
完整消费一个答案不需要关闭——提前跳出循环的才需要，try-with-resources 覆盖这一点。

失败在拉取处浮现：提供商拒绝的请求在返回任何东西之前就失败，答案中途断开的连接在迭代过程中失败。

## 带工具的流式

`ToolCallingChatClient.stream` 把各轮的流拼接成一个序列：循环在拉取内部推进，执行某一轮的工具批，
并在下一个事件到达前打开下一轮。轮与轮之间的边界是协议自己的，不合成任何标记。
