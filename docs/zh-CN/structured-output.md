# 结构化输出

[English](../en/structured-output.md) | **中文**

你可以要求模型以 JSON 作答，或者以符合某个 schema 的 JSON 作答。schema 由你的编解码器生成，因此
模型被约束到你的编解码器恰好能读回的 JSON。

## 生成 schema

`JsonCodec` 能为任意类型推导出 schema，两个方向都有：

- `generateEncodeSchema(type)` 描述 `encode` 写出什么；
- `generateDecodeSchema(type)` 描述 `decode` 接受什么。

两者可以不同，因为绑定器可能写出一个它读不回的属性。要向模型要一个值，你要的是 decode schema——
你的编解码器会接受的那份 JSON：

```java
JsonSchema schema = codec.generateDecodeSchema(Person.class);
```

类型是 `Type` 而不是 `Class`，因此泛型类型带着它的类型参数一起到达：`List<Order>` 描述的是一个
order 数组。

schema 通常由编解码器生成，但当它并非从某个类型推导而来时，也可以用 `JsonSchemaBuilder` 手工拼装。

## 提出要求

答案应取的形状放在选项里的 `ChatResponseFormat` 上：

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

三种模式是 `TYPE_TEXT`（普通文本）、`TYPE_JSON`（任意合法 JSON）和 `TYPE_JSON_SCHEMA`（符合 schema 的
JSON）。`strict` 要求提供商强制执行 schema，而不只是尽量照它来。协议若无法关掉强制，它照样强制，因此
`strict = false` 在那里拿到的仍是符合 schema 的答案——比要求更严，绝不会更松。这只针对调用确实会发出的
schema：`TYPE_JSON` 下没有 schema 可强制，`strict` 根本不会发出。

schema 是一个 `JsonSchema` 值，因此这里没有任何东西绑定 JSON 库：你选的编解码器产出它，模型被约束到
那个编解码器读回的形状。

## 把答案读回来

```java
Person person = codec.decode(response.getText(), Person.class);
```

## 各提供商的支持

每个提供商模块都把这项要求翻译成自己的协议。

协议无法表达所要求的答案形状时，调用会失败，而不是以普通文本作答——一个看上去像成功的答案，是代价最高
的一种出错方式。例如 Anthropic 协议没有承载 schema 名字或描述的成员，也无法关掉对 schema 的强制，
因此 `name`、`description` 和 `strict` 在那里根本不会发出，调用带着它能兑现的那份 schema 继续进行。
换作它不支持的 `type`，则会被拒绝。
