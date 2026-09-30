# 与其他 Java 库的对比

[English](../en/comparison.md) | **中文**

本文讲的是行为，而不是代码：每个库替你做什么，以及它们之间的界线在哪里。它涵盖你最可能在三者之间
做选择的三种形态——工具集、框架、厂商 SDK——以及 synapse4j 处在什么位置。

## 一览

| | synapse4j | LangChain4j | Spring AI | 官方 SDK |
|---|---|---|---|---|
| 对话记忆 | 无；由你持有 | 内置（`ChatMemory`，窗口、淘汰、持久化） | 内置（`ChatMemory` 加 advisor） | 无 |
| 提供商抽象 | 一套模型 + 提供商模块 | 一套模型 + 各提供商模块 | `ChatModel` 加 `ChatClient` | 仅一家提供商 |
| 框架耦合 | 无 | 无（一个工具集） | Spring，与 Boot 配合最佳 | 无 |
| JSON 库 | 由你选，接口背后的实现可换 | Jackson | Jackson | 自带，生成的 |
| HTTP 客户端 | 由你选，接口背后的实现可换 | 自带客户端 | Spring 的 HTTP | 自带（OkHttp） |
| API 风格 | 阻塞；流是拉取的 | 阻塞 + 流式回调 | 阻塞 + 流式（Flux） | 阻塞 + 流式 |
| 声明式接口 | 无 | 有（`AiService`） | 无 | 无 |
| 工具调用 | `Tool` 加执行器与循环 | `@Tool` 注解 | `@Tool` 加 `ChatClient` | 原始调用 |
| 扩展模型 | 开放结构，外加可透传的附加字段 | 自己的类型 | 自己的类型 | 生成的类型 |

## LangChain4j

一个工具集：它为整个应用备齐各个组件——聊天模型、嵌入、RAG、agent、记忆——并且不绑定框架。

它的核心是声明式的 **`AiService`**：你写一个普通接口，加上注解，库生成实现。

```java
interface Assistant {
    @SystemMessage("你是一个乐于助人的助手。")
    String chat(@UserMessage String question);
}
```

对话记忆是一等组件：`ChatMemory` 保存历史，带窗口上限、淘汰机制和可插拔的持久化实现。默认的服务会
替你保留最近若干条消息的窗口。

当你想要开箱即用、且不介意采用它的类型和 SPI 时，选它。

## Spring AI

Spring 原生。`ChatModel` 抽象一家提供商；`ChatClient` 是链式调用的入口，`ChatMemory` 加 advisor
提供记忆和其他横切行为。

记忆通过 advisor 接线——`MessageChatMemoryAdvisor` 从 `ChatMemory` 取出历史、把回答写回——因此它
是按客户端可选加入的，而把它限定到正确的对话是你的工作。

当你的应用已经在用 Spring Boot、且希望 LLM 调用看起来和应用其余部分一样时，选它。

## 官方 SDK

`openai-java` 与 `anthropic-java` 是生成的、按提供商划分的客户端。它们暴露某一家厂商的完整 API 面
——批处理、文件、所有东西——请求类型不可变、基于 builder。

没有与提供商无关的模型，也没有记忆：每个 SDK 只说一家提供商，在它们之间切换意味着重写调用。

当你只用一家提供商、且想直接访问它的整个 API（尤其是聊天之外的功能）时，选它。

## synapse4j 处在什么位置

synapse4j 是四者中最小的。它保留中立模型和工具调用，略去其他库捆绑的东西：没有对话记忆、没有框架，
也不固定 JSON 库或 HTTP 客户端。历史由你保存，组件由你挑选。

这有代价，直说：保存对话的循环由你写，库是阻塞式而非响应式，覆盖的提供商也比其他库少。换来的是一个
属于你的技术栈——更换 JSON 库、HTTP 客户端或提供商都不触碰任何核心抽象，依赖树也保持很小。

一个大致的指南：

- **一家提供商、直接访问 API**——官方 SDK。
- **一个 Spring Boot 应用**——Spring AI。
- **带记忆、RAG 和 agent 的工具集**——LangChain4j。
- **一个由你自己组装的、小巧而不强加主张的层**——synapse4j。

## 来源

- [LangChain4j `AiServices`](https://docs.langchain4j.dev/apidocs/dev/langchain4j/service/AiServices.html)
  及其 chat memory 组件。
- [Spring AI 参考——聊天客户端与 Anthropic 迁移](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-migration.html)。
- [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) 与 OpenAI Java SDK。
