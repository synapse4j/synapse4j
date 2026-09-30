# 调用模型

[English](../en/model.md) | **中文**

不管底下是哪家提供商，每次调用和每个答案都由同一小组类型构成。本文说明这些类型是什么。

## 一次调用和它的答案

一次调用是一个 `ChatRequest`，它携带：

- **系统消息**——模型回答时所处的指令，或者没有；
- **对话**——`historyMessages`，一段往来已经覆盖的全部内容；`pendingMessages`，本次调用新增的消息；
- **工具**——模型可以调用的工具；
- **选项**——本次调用的模型、温度、答案形状等等；
- 一个可选的**上下文**，把这次调用与某段对话关联起来。

答案是一个 `ChatResponse`。它的 `message` 是助手的这一轮，类型是 `ChatMessage`——和构建请求用的是
同一个类型，因此可以原样追加到下一个请求。旁边还有 `finishReason`、`usage`、回答的 `model`、提供商
给这次响应的 `id`、响应 `headers`，以及一个承载提供商特有字段的袋子。

## 消息

一条消息有一个角色和一个内容部分列表。角色是普通字符串——`ChatRole` 给出常见的几个：`system`、
`user`、`assistant`、`tool`——因此提供商或应用可以使用库从未听说过的角色。

```java
ChatMessage.user("天空为什么是蓝色的？");          // 用户这一轮
ChatMessage.system("用一句话回答。");              // 指令
ChatMessage.assistant("因为散射。");               // 手写的一轮模型输出
```

消息还可以携带一个 `id`，供你自己记账，以及提供商特有的字段。

## 内容部分

一条消息的内容是一个内容部分列表，每个部分都是下列之一：

- `TextPart`——发给模型或由模型产生的文本；
- `ReasoningPart`——模型的推理，与答案分开保存，因为应用通常会隐藏它、单独计费，或者必须原样发回；
- `ToolCallPart`——模型请求调用工具：调用 id、工具名，以及作为 JSON 文本的参数；
- `ToolResultPart`——对一次工具调用的回答：调用 id、工具名、一个或多个部分，以及调用是否失败；
- `MediaPart`——图像、音频、视频或文档。

`ContentPart` 不是 final：提供商或应用可以继承它，加入库没有建模的类别。

多数消息只有一个文本部分，添加文本有简写：

```java
ChatMessage.user("你好").addText("，世界");
```

## 选项

`ChatOptions` 承载一次调用可以调节的东西：

| 字段 | 设置什么 |
|---|---|
| `model` | 调用哪个模型 |
| `temperature`、`topP` | 采样 |
| `maxOutputTokens` | 生成 token 的上限 |
| `reasoningEffort` | 模型应该推理多少 |
| `toolChoice`、`toolChoiceName` | 模型是否可以、以及可以调用哪个工具 |
| `responseFormat` | 散文、JSON，或符合 schema 的 JSON |
| `httpOptions`、`headers` | 本次调用的传输层设置 |
| `extras` | 提供商特有的字段 |

每个字段都是可选的：`null` 表示「没有意见」，客户端的默认值会补上。只有至少两家提供商都认同的旋钮
才放在这里；某一家独有的字段放进 extras 袋子。

## 提供商特有的字段

库没有建模的东西不会丢。每个节点——消息、内容部分、工具、选项——都能携带一个 `ProviderExtras`
袋子：一个从点分路径到值的映射，会原样合并进要发出的 JSON：

```java
message.getOrCreateExtras().putRaw("thinking.budget_tokens", 2048);
```

提供商发来的、库叫不出名字的字段，保留在它来自的那个节点上，节点再次发出时原样写回。原样通过的东西
从不翻译——见[设计与取舍](design.md)。

## 上下文

`ChatContext` 把一次调用与一段对话关联起来：一个会话 id、当前的轮次、进行中这次往来的 request 与
response，以及一个库从不读取、也从不发送的属性映射。

你很少需要碰它。除了属性，库会填好一切；需要这段对话的工具会把它作为参数收到。属性是你自己数据的
进程内通道，里面的东西不会进入请求。
