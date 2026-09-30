# synapse4j

[English](README.md) | **中文**

一个用来调用 LLM 提供商的轻量级 Java 库。你只写一次调用，它就能对着你指定的任意提供商工作；JSON
库、HTTP 客户端和提供商都由你来选，库别的什么都不管。

它涵盖 JSON Schema 生成与 JSON 序列化、一套与提供商无关的聊天模型（阻塞与流式兼具），以及端到端的
工具调用。提供商模块单独发布。

## 模块

| 模块 | 是什么 |
|---|---|
| `synapse4j-core` | 与提供商无关的模型、JSON/HTTP 接口、工具调用 |
| `synapse4j-jackson` | 基于 Jackson 的 `JsonCodec` |
| `synapse4j-http-jdk` | 基于 JDK `java.net.http` 的 `HttpClient` |
| `synapse4j-http-apache` | 基于 Apache HttpClient 5 的 `HttpClient` |
| `synapse4j-http-restclient` | 基于 Spring `RestClient` 的 `HttpClient` |
| `synapse4j-openai` | OpenAI Chat Completions 与 Responses 协议 |
| `synapse4j-anthropic` | Anthropic Messages 协议 |
| `synapse4j-spring-boot-starter` | Spring Boot 自动配置 |
| `synapse4j-bom` | 供使用方对齐版本的 BOM |

## 文档

- [入门](docs/zh-CN/getting-started.md)——组装客户端、发起调用、流式接收、运行工具、要求结构化
  输出。无需任何框架。
- [设计与取舍](docs/zh-CN/design.md)——库为什么设计成这个样子。
- [完整文档索引](docs/zh-CN/index.md)

English docs: [docs/en](docs/en/index.md).

## 环境要求

- Java 21 或更新版本
- Maven 3.6.3 或更新版本（没有 Maven Wrapper，直接运行 `mvn`）

## 构建

```bash
mvn verify    # 编译、强制格式检查、运行测试、校验空值契约
```

请在仓库根目录运行 Maven：Spotless 相对运行 Maven 时的目录解析其配置文件。

## 许可证

Apache License 2.0——见 [LICENSE](LICENSE)。
