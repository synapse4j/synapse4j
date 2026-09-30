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
（`historyMessages`），并追加助手的回答。每收到一个答案就调用一次。工具调用循环会为它消费掉的答案调用
它；最后一个答案由你折入。

## 历史、待发消息与系统消息

一个请求用两个列表加一个槽位承载对话：

- `historyMessages`——当前对话，一段往来已经覆盖的全部内容；
- `pendingMessages`——本次调用新增的消息，它们都会发出；
- `systemMessage` 槽位——框架性指令，是替换而非累积。

库从不裁剪这两个列表。为了塞进上下文窗口而丢掉旧消息，是你刻意的行为，而不是库背着你做的。

## 服务端对话

有些协议替你保存对话。例如 OpenAI Responses API 可以保存每个答案，并从上一次的响应 id 继续，而不是
重发整段记录。

这改变的是客户端发出的内容，而不是你写代码的方式：你照旧用同样的方式调用 `chat` 和 `continueWith`。
协议有状态的客户端会重写 `continueWith`，记录它下一次调用需要的东西——上一次的响应 id——并决定答案
该进哪个列表。

服务端是否保存答案，是每次调用的决定。在 OpenAI Responses 客户端上，它是 `OpenAiConfig` 上的
`storeResponses` 选项；不设置就沿用端点自己的默认值。

## 自己持久化对话

如果你自己保存对话——数据库、文件——实现一个 `ChatCustomizer`，在它的钩子触发时把 request 和
response 写出去：

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

一个 `ChatCustomizer` 为每个步骤提供一个钩子——`customizeRequest`、`customizeResponse`、
`customizeStreamEvent`——没重写的就什么都不做。每个钩子拿到的是客户端已经持有的那个值，并就地修改
它。把它注册在内层客户端上，就能看到工具调用循环的每一轮。

## 在提供商之间迁移

一段对话可以用另一个客户端继续——另一个模型，或另一家提供商。两边用的消息是同样的类型。extras
袋子里的提供商特有字段按读到的原样携带；新提供商是否理解它们由你判断，把它们过滤掉也是你的事。见
[设计与取舍](design.md)。
