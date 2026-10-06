# 对话

[English](../en/conversations.md) | **中文**

库不保存任何对话状态。本文说明当你自己持有对话时，它是怎么运作的。

## 请求由你持有

一段对话就是一个你保存并复用的 `ChatRequest`。每一轮，你加上用户的消息，发出请求，再把答案折回来：

```java
ChatRequest request = new ChatRequest()
        .systemMessage("你是一个乐于助人的助手。")
        .addUserMessage("天空为什么是蓝色的？");

ChatResponse first = client.chat(request);
client.continueWith(request, first);

request.addUserMessage("那为什么日落时是红色的？");
ChatResponse second = client.chat(request);
```

`continueWith(request, answer)` 做两件事：把本次调用发出的消息（`pendingMessages`）归档进当前对话
（`historyMessages`），并追加助手的答案。每收到一个答案就调用一次。工具调用循环每消费一个答案都调用
它一次；最后一个答案要由你自己折入。

## 历史、待发消息与系统消息

一个请求用两个列表加一个槽位承载对话：

- `historyMessages`——当前对话，一段往来已经覆盖的全部内容；
- `pendingMessages`——本次调用新增的消息，它们都会发出；
- `systemMessage` 槽位——定调的内容，是替换而不是累积。

库从不裁剪这两个列表。为了塞进上下文窗口而丢掉旧消息，是你刻意的行为，而不是库背着你做的。

## 服务端对话

有些协议替你保存对话。例如 OpenAI Responses API 可以保存每个答案，并从上一次的响应 id 继续，而不是
重发整段记录。

这改变的是客户端发出的内容，而不是你写代码的方式：你照旧用同样的方式调用 `chat` 和 `continueWith`。
协议本身有状态的客户端会重写 `continueWith`，把上一次的响应 id 写进请求的选项 extras 里。这个 id 锚定整条
链：一旦写进去，下一次 `chat` 只发送待发消息加上这个 id——历史仍留在请求里作为你的本地记录，但服务端
已经持有它，不会再收到一遍。这一重写还决定每个答案该进哪个列表。服务端存下的答案照常归档进历史，并把
id 向前推进；服务端没有存下、而请求上又有 id 的答案，则改而并入待发消息——若归档进历史，它会在之后每次
调用中被跳过，因为带锚的调用恰恰不会发送记录中的这一部分。

OpenAI Responses 客户端是 `OpenAiResponsesChatClient`，由[入门](getting-started.md#2-构建一个客户端)
里那三部分构建而成：

```java
import io.github.synapse4j.openai.OpenAiResponsesChatClient;

OpenAiConfig config = new OpenAiConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));
config.setStoreResponses(true);

ChatClient client = new OpenAiResponsesChatClient(http, codec, config);
```

服务端是否保存答案，是每次调用各自决定的事。Responses 客户端从 `OpenAiConfig` 上的 `storeResponses`
选项读取它，调用也可以通过自己的选项 extras 设置 `store` 成员本身——extras 里的 `store` 路径会落成
请求顶层的 `store` 成员，并覆盖本次调用的 `storeResponses`；两者都不设置时，沿用端点自己的默认值。

## 自己持久化对话

如果你自己保存对话——数据库、文件——实现一个 `ChatCustomizer`，在它的钩子触发时把请求与响应写出去：

```java
client.addChatCustomizer(new ChatCustomizer() {
    @Override
    public void customizeRequest(ChatClient client, ChatRequest request) {
        // 每次发送前运行
    }

    @Override
    public void customizeResponse(ChatClient client, ChatResponse response) {
        // 每个答案到达时运行
    }
});
```

一个 customizer 为每个步骤提供一个钩子——`customizeRequest`、`customizeResponse`、
`customizeStreamEvent`（流式答案的每个 `ChatStreamEvent` 调用一次）——没重写的什么都不做。每个钩子
拿到的是客户端已经持有的那个值，并就地修改它。把它注册到
[`ToolCallingChatClient`](tools.md#运行调用)上，就能看到工具调用循环的每一轮：注册会交给它所包装的
客户端，因此每个钩子在每轮运行一次。

## 在提供商之间迁移

一段对话可以用另一个客户端继续——另一个模型，或另一家提供商。两边用的消息是同样的类型。extras
映射里的提供商特有字段按读到的原样携带；新提供商是否理解它们由你判断，把它们过滤掉也是你的事。见
[设计与取舍](design.md)。
