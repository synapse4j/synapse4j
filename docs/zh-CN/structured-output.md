# 结构化输出

[English](../en/structured-output.md) | **中文**

你可以让模型以 JSON 作答，也可以让它以符合某个 schema 的 JSON 作答。schema 由你的编解码器生成，因此
模型只能产出你的编解码器恰好能读回的 JSON。

## 生成 schema

`JsonCodec` 能为任意类型推导出 schema，两个方向都可以：

- `generateEncodeSchema(type)` 描述 `encode` 写出什么；
- `generateDecodeSchema(type)` 描述 `decode` 接受什么。

两者可能不同，因为绑定器可能写出一个它读不回的属性。要向模型要一个值，你要的是 decode schema——
你的编解码器会接受的那份 JSON：

```java
JsonSchema schema = codec.generateDecodeSchema(Person.class);
```

类型是 `Type` 而不是 `Class`，因此泛型类型会带着它的类型实参一起出现：`List<Order>` 描述的是一个
`Order` 数组。

schema 通常由编解码器生成，但当它并非从某个类型推导而来时，也可以用 `JsonSchemaBuilder` 手工组装。

## 提出要求

答案应取的形状放在选项上的 `ChatResponseFormat` 里：

```java
record Person(String name, int age) {}

ChatResponseFormat format = new ChatResponseFormat();
format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
format.setName("person");
format.setSchema(codec.generateDecodeSchema(Person.class));
format.setStrict(true);

ChatOptions options = new ChatOptions();
options.setResponseFormat(format);
```

三种模式是 `TYPE_TEXT`（普通文本）、`TYPE_JSON`（任意合法 JSON）和 `TYPE_JSON_SCHEMA`（依照 schema
的 JSON）。`strict` 要求提供商强制 schema，而不只是尽量照着它来。无法关闭强制的协议无论如何都会强制，
因此在那里 `strict = false` 拿到的仍是符合 schema 的答案——比要求的更严，绝不会更松。这只对调用确实
会发出的 schema 成立：`TYPE_JSON` 下没有 schema 可强制，`strict` 根本不会发出。

schema 是一个 `JsonSchema` 值，因此这里没有任何东西绑定 JSON 库：它由你选的编解码器生成，
模型必须产出与该编解码器读回相同的形状。

## 把答案读回来

```java
Person person = codec.decode(response.getText(), Person.class);
```

## 各提供商的支持

每个提供商模块都把这项要求翻译成自己的协议。

无法表达调用方所要求的答案形状的协议，宁可让调用失败，也不以普通文本作答——一个看上去像成功的答案，是
代价最高的出错方式。例如 Anthropic 协议没有承载 schema 名字或描述的成员，也无法关闭它对 schema
的强制，因此 `name`、`description` 和 `strict` 在那里根本不会发出，调用带着它能兑现的那份 schema
继续进行。相比之下，不支持的 `type` 则直接拒绝。
