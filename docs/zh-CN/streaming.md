# 流式

[English](../en/streaming.md) | **中文**

答案可以一边到达一边消费，也可以等它完整到达。两种方式请求相同，不同的只是拿到的东西——完整的答案，
或者按提供商发送顺序逐个到达的事件。

```java
try (ChatStream stream = client.stream(request)) {
    for (ChatStreamEvent event : stream) {
        if (event.getDelta() != null) {
            System.out.print(event.getDelta().getText());
        }
    }
}
```

## 事件

一个事件就是一个协议事件，一一对应，并按到达顺序排列。它携带协议自己的 `eventType`——你可以据此分派
——另外，在有归一化视图的时候还带上它：一条 `delta` 消息，内含这个事件新增的内容部分；一个
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

循环跑到结束后，它持有 `chat` 会返回的那个轮次。它从不阻塞，也从不驱动消费——它报告迭代器已经折叠的
内容。折叠在消费迭代器的那个线程上运行，因此也要从那个线程读取它：在流仍被消费时从别的线程调用它会与
折叠发生竞争；流的各个方法中只有 `close()` 可以从任意线程调用。

## 拉取，以及提前停止

迭代是惰性且阻塞的：`hasNext()` 等待下一个事件，提供商也因此按调用者的消费速度发送，不会抢在前面。迭代
的线程就是事件到达的线程。

一个流只能走一遍：第一次调用 `iterator()` 得到那个迭代器，第二次调用会抛异常。关闭会释放流背后的连接，
取消仍在进行中的响应；它是幂等的，且可从任意线程调用。跑到结尾的流会自行释放同一条连接，因此完整消费
一个答案不需要关闭——提前跳出循环的才需要，try-with-resources 覆盖这一点。

失败在拉取的地方暴露出来：提供商拒绝的请求在返回任何东西之前就失败，答案中途断开的连接在迭代过程中失败。

## 带工具的流式

`ToolCallingChatClient.stream` 把各轮的流拼接成一个事件序列。轮与轮之间的边界是协议自己的，不合成
任何标记。
