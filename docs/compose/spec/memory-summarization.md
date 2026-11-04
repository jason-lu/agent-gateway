---
feature: memory-summarization
status: delivered
updated: 2026-11-05
source: main
---

# Conversation Memory Summarization

## Report

**What was built** — a `MemoryCompressor` in `llm-core` folds older turns into an incremental summary so the request payload stays bounded. Folding is non-destructive: `ConversationMemory.messages()` keeps every raw turn (what `/history`, the web history API and persistence see), the `【历史对话摘要】` summary marker sits at the fold point, and the new `ConversationMemory.context()` — `[system 原提示][摘要 system][最近原文]` — is the bounded view actually sent to the model (`withUser` and `ToolLoop` build payloads from it). The summary is identified by the `【历史对话摘要】` marker, and the verbatim tail always starts at a `user` turn (rolling back if needed) so no run is split and no tool result is orphaned. Both `web` (`ChatService`) and `cli` (`ChatConsole`) compress on the request path; compression failure never blocks the user-visible turn. Thresholds are configurable (`agent.memory.*` / `AGENT_MEMORY_*`, default 20/8, on by default) and validated at startup. Compression events are logged via slf4j (INFO on success, WARN on failure, DEBUG on skip); `llm-core` gained `slf4j-api`, `cli` gained `slf4j-simple`. `/history` and the web history API hide the summary marker.

**Verification** — `./gradlew test` PASS across llm-core, cli, web. Independent read-only review found no blockers; its concerns were fixed (CLI `/history` over-filtering by content, web/cli divergent validation when disabled, null exception message, imprecise skip log) or documented as known limitations (a system prompt starting with the summary marker is indistinguishable from a summary). The 2026-11-05 revision (non-destructive folding, see Journey log) re-verified with `./gradlew test` PASS.

## [S1] Problem

`ConversationMemory` 只追加不裁剪（`llm-core/.../ConversationMemory.java`），每轮通过 `withUser()` 把**完整历史**拼进请求发给 LLM。长对话的 payload 线性增长，最终撞模型上下文上限报错；同时每轮重复发送旧内容，成本无界。

需要在**保留可继续工作的上下文**的前提下，把较早的历史压成摘要，使 payload 大小有界。用户已选定摘要（而非简单截断 / 滑动窗口）作为压缩方式，触发条件用**消息条数**。

## [S2] Design

### [S2.1] 压缩策略：结构头 + 摘要 + 最近 N 条原文

压缩**不丢原文**：`ConversationMemory.messages()` 永远保留完整对话（`/history`、历史 API 与持久化都读它），被折叠的只是发给模型的上下文视图 `context()`。

存储布局（摘要标记停在折叠分界点，原文轮次分列两侧）：

```
[0]       system   原系统提示（若创建会话时提供）
[1..p]    user/assistant/tool   已折叠进摘要的原文轮次（留在历史中，不发给模型）
[p+1]     system   【历史对话摘要】<summary>     ← 折叠分界点
[p+2..k]  user/assistant/tool   最近的原文轮次（keep-messages 条左右）
```

`context()` 视图（真正发给模型的 payload）：

```
[0]     system   原系统提示（若存在）
[1]     system   【历史对话摘要】<summary>
[2..]   user/assistant/tool   最近的原文轮次
```

**不变量**

1. 索引 0 恒为原 system 提示（若存在）；它永不参与压缩、永不改写。**例外**：若原 system 提示本身以 `SUMMARY_PREFIX` 开头，则与摘要无法区分，会被当作旧摘要折叠掉（见 Journey log，已知限制）。
2. 摘要消息恒为 `system` 角色，内容以固定前缀 `MemoryCompressor.SUMMARY_PREFIX = "【历史对话摘要】"` 开头，靠前缀识别（而非靠位置）；折叠分界点取**最后一条**摘要标记。至多保留一条摘要标记——增量压缩把旧标记的正文并入新摘要后移除旧标记。
3. 原文段（verbatim tail）**起点必须落在 `user` 消息上**。若按条数算出的切点不是 `user`，向更早方向回退到最近的 `user` 消息——宁可多留几条，也不切断一轮问答、不孤立 `tool` 结果。回退带来的余量通常 ≤ 3 条。
4. 若非结构部分找不到 `user` 切点（例如全部是 tool/assistant 残片），跳过本轮压缩。
5. **原文轮次永不删除**：折叠只移动摘要标记；`messages()` 里的 user/assistant/tool 轮次自进入历史起一直保留，`/history` 与持久化因此永远完整。

**触发与结果**

- 触发：`context().size() > maxMessages`（含 system / 摘要消息，评估时机见 S2.6）。按 context 而非 `memory.size()` 计数——历史只增不减，用 `size()` 会在长会话里每轮误触发。
- 压缩后 `context()` 规模 ≈ `2 + keepMessages`（± S2.1-3 的回退余量），随后每轮 +2，涨回 `maxMessages` 再压下一轮；`messages()` 只增不减。
- 边界合法前提：`keepMessages >= 2` 且 `maxMessages > keepMessages + 2`，配置期校验失败则拒绝启动（见 S2.5）。

**增量摘要**

第二次及以后的压缩不重放全部历史：

- **旧摘要**（去掉前缀后的正文）作为 `previousSummary` 传入；
- **新变旧的原文轮次**（上次 tail 中被挤出去的部分）作为 `turns` 传入；
- 二者一起合成新摘要，落在新的折叠分界点；旧摘要标记被移除，更早已折叠的原文继续留在 `messages()`、继续只由摘要代表。

这样单次压缩的 LLM 输入与 `keepMessages` 同阶，不随会话总长度增长。

### [S2.2] `MemoryCompressor`（`llm-core`，包 `dev.kualu.llm.memory`）

```java
package dev.kualu.llm.memory;

/** Compresses conversation history into an incremental summary. */
public final class MemoryCompressor {

    /** @param maxMessages  触发阈值（context 条数，含 system / 摘要）
     *  @param keepMessages 压缩后保留的原文条数
     *  @param instruction  摘要指令（null → 用默认指令） */
    public MemoryCompressor(LlmClient llm, int maxMessages, int keepMessages, String instruction)

    /**
     * 若 {@code memory.context().size() > maxMessages} 则压缩：原文保留在
     * {@code messages()}，摘要标记移到折叠分界点。
     * 摘要 LLM 调用失败时不动历史、只记 WARN——压缩是优化，不属于用户可见的那一轮。
     *
     * @param context 日志关联标识（web 传 {@code "session=" + id}，cli 传 {@code "cli"}），可为 null
     * @return true 表示发生了压缩
     */
    public boolean maybeCompress(ConversationMemory memory, String context)

    static String summarizePrompt(String previousSummary, List<ChatMessage> turns) // 测试可见
}
```

- 依赖 `LlmClient.chat(List<ChatMessage>)` 做一次非流式摘要调用；不新增抽象接口（当前只有一个实现、一处调用）。
- 测试用可控 `LlmClient` 子类注入固定摘要文本，不打真实网络。
- 不在 `ConversationMemory` 内部做压缩——它是无行为容器，保持 dumb；带 LLM 依赖的策略放这里。
- 线程安全：本类无可变状态；并发由调用方的 per-session 锁（web）或单线程（cli）保证，与现状一致。
- 日志走 slf4j（事件与级别见 S2.8）；`context` 只用于日志前缀，不参与压缩逻辑。

### [S2.3] `ConversationMemory` 变更

```java
/** 整体替换历史（防御性拷贝）。供压缩器、tool loop commit、会话恢复使用。 */
public ConversationMemory replaceAll(List<ChatMessage> newHistory)

/** 有界的请求视图：[system 原提示][摘要][折叠分界点之后的原文]。 */
public List<ChatMessage> context()
```

- `replaceAll`：`null` 元素 / `null` 入参 → NPE；空列表合法（等价于 `clear()` 后无内容）。
- `messages()` 返回完整历史（含已折叠原文），供 `/history`、历史 API 与持久化读取；`context()` 只返回发给模型的有界视图；`withUser()` 基于 `context()` 拼请求。
- 压缩器据此识别 system / 摘要 / tail（摘要按前缀识别，折叠分界点取最后一条摘要标记）。
- 不引入 `removeRange` / `subList` 之类的细粒度 API——压缩只移动摘要标记，整表替换最简单。

### [S2.4] 摘要 Prompt

摘要调用的 `messages` 为单条 `system` 指令 + 一条 `user` 载荷。

**默认指令不写死在代码里**，而是作为 classpath 资源与实现解耦：

| 项 | 位置 |
|---|---|
| 默认指令正文 | `llm-core/src/main/resources/dev/kualu/llm/summarize-instruction.txt` |
| 加载 | `MemoryCompressor.loadDefaultInstruction()`，UTF-8 读入后 `strip()` |
| 覆盖 | 构造参数 `instruction` / `agent.memory.instruction` / `AGENT_MEMORY_INSTRUCTION` |
| 缺失 | 视为打包错误，类初始化直接抛 `IllegalStateException`（不做静默回退） |

调提示词（摘要要保什么、什么语气、输出什么）只改 `.txt`，不动 Java 代码、不用重新编译逻辑。指令正文覆盖：用户目标与约束、关键决策及理由、具体事实（名称/路径/ID/命令/版本/报错原文）、已完成与未决事项；用与对话相同的语言，密度优先，只输出摘要本体。

`user` 载荷格式（**仍在代码里**——它是请求装配结构而非提示词措辞，`summarizePrompt` 一处组装）：

```text
[Previous summary]
（首次压缩时为 "(none)"，否则为去掉前缀的旧摘要正文）

[Older turns]
role: content
role: content
...
```

返回文本写回时加上前缀：`【历史对话摘要】` + 返回文本。

**后端兼容性**：摘要以独立 `system` 消息出现在折叠分界点（OpenAI 风格普遍支持）。若目标后端只允许单条 system，改为把摘要拼进原 system 提示末尾——改动集中在 `buildSummaryMessage` 一处。

### [S2.5] 配置

| Web key | CLI env | 默认 | 说明 |
|---|---|---|---|
| `agent.memory.enabled` | `AGENT_MEMORY_ENABLED` | `true` | 总开关，关掉即回退到全量历史 |
| `agent.memory.max-messages` | `AGENT_MEMORY_MAX_MESSAGES` | `20` | 触发阈值（发给模型的 context 条数，含 system / 摘要） |
| `agent.memory.keep-messages` | `AGENT_MEMORY_KEEP_MESSAGES` | `8` | 压缩后保留原文的条数 |
| `agent.memory.instruction` | `AGENT_MEMORY_INSTRUCTION` | 见 S2.4 | 自定义摘要指令 |

校验（启动期失败，不做静默回退）：

- `keepMessages >= 2`
- `maxMessages > keepMessages + 2`
- 非正整数 → 拒绝启动

`enabled=false` 时不注册压缩器（web）/ 不调用（cli），行为与现状逐字节一致，**且不做上述校验**——禁用的特性不应因未被使用的参数拦住启动（web 与 cli 一致）。

### [S2.6] 集成

**时机：在构建 payload *之前* 压缩**，这样当轮请求就已经是有界的；而不是压完这轮、下轮才生效。

`web` — `ChatService.chat` / `ChatService.chatStream`（`web/.../ChatService.java:38`）的 `synchronized` 块内：

```java
synchronized (session.memory()) {
    compressor.maybeCompress(session.memory(), "session=" + session.id());  // 失败只记 WARN，继续
    String reply = llmClient.chat(session.memory().withUser(content));
    session.memory().addUser(content).addAssistant(reply);
    sessionStore.update(session);
    return reply;
}
```

- 压缩器通过 Spring `@ConfigurationProperties(prefix = "agent.memory")` 注入，未启用时不占 bean。
- 压缩期间的 LLM 调用发生在 per-session 锁内，与既有"同一会话串行"语义一致；锁持有时间变长是已知代价（见 Journey log）。
- `ChatService.history()` 现在只筛 `user`/`assistant`，摘要的 `system` 消息天然不进历史 API——保持不变，本特性不改 HTTP 契约。
- 启动期由 `ChatService`（或配置 bean）打一条 `INFO`：`memory-compress configured: ...`（见 S2.8）。

`cli` — `ChatConsole`（`cli/.../ChatConsole.java`）在 `memory.withUser(message)` 之前同样调用 `maybeCompress(memory, "cli")`。压缩器由 `Main` 按 env 构造后传入；`ChatConsole` 现有构造器保持兼容（新增一个重载或接受 nullable 压缩器，null = 关闭）。

- `/history`、`/status` 的行为不变；`/status` 的 `memory size` 仍是 `memory.size()`（完整历史条数，压缩不减小它——历史要完整保留）。
- `/clear` 仍然 `memory.clear()`，摘要随历史一起丢弃。

### [S2.7] 失败语义

| 情况 | 行为 |
|---|---|
| 未到阈值 | 直接返回 false，不发任何请求 |
| 摘要 LLM 调用抛 `LlmException` / 其它异常 | **不改历史**、不向上抛；记 `WARN`（见 S2.8），本轮按当前 context 照常发请求；下一轮重试 |
| 配置非法 | 启动期失败（web）/ 构造期抛 `IllegalArgumentException`（cli） |
| 找不到合法 `user` 切点 | 跳过压缩，返回 false |
| 压缩后恰好触发 HTTP 4xx（如 payload 超限） | 与现状一致：映射为上游错误，不因本特性改变错误面 |

核心原则：**压缩永不阻断用户可见的那一轮回复**。它是 payload 的优化路径，不是正确性路径。

### [S2.8] 日志

本仓库目前**没有任何日志调用**（`cli` 只用 `System.out/err`，`web` 也没打过一条）。本特性引入首个日志点，门面用 **slf4j-api**：

- `llm-core/build.gradle.kts` 增加 `implementation("org.slf4j:slf4j-api")`；`module-info.java` 加 `requires org.slf4j;`（现仅有 `java.net.http` / `com.google.gson`）。
- `web` 零改动——Spring Boot 自带 logback 绑定。
- `cli` 增加 `runtimeOnly("org.slf4j:slf4j-simple")`，输出走 stderr，不污染 stdout 的对话内容。

**事件与级别**（logger 名统一 `dev.kualu.llm.memory.MemoryCompressor`）：

| 事件 | 级别 | 示例 |
|---|---|---|
| 压缩成功 | `INFO` | `memory-compress[session=abc123]: context 24 -> 6 (folded 14 turns, summary 812 chars, 1370 ms)` |
| 摘要失败 | `WARN` + throwable 栈 | `memory-compress[session=abc123] FAILED: <reason>; history left unchanged (size 24)` |
| 无合法切点跳过 | `DEBUG` | `memory-compress[session=abc123] skipped: no user-turn cut point (size 24)` |
| 配置加载（启动期，由调用方打） | `INFO` | `memory-compress configured: enabled=true, max-messages=20, keep-messages=8` |

**不记录**：未到阈值的调用。那占绝大多数轮次，打了就是噪音；需要排查时开 `DEBUG` 看 skip 事件即可。

**上下文关联**：`maybeCompress(memory, context)` 的 `context` 直接进日志方括号前缀——web 传 `session=<id>`（`ChatService` 已持有 `Session`），cli 传 `cli`；传 `null` 时方括号整体省略，前缀退化为 `memory-compress:`。

**INFO 行的指标口径**（便于 grep，也为后续接监控留好字段名）：

| 字段 | 含义 |
|---|---|
| `context N -> M` | 压缩前后发给模型的 context 条数 |
| `folded K turns` | 本次被并入摘要的原文条数 |
| `summary S chars` | 新摘要正文长度（不含 `【历史对话摘要】` 前缀） |
| `elapsed T ms` | 摘要 LLM 调用耗时 |

日志文案不作为对外契约。`llm-core` 单测只断言行为与返回值，不断言日志文本（默认 NOP 绑定）；`web` 侧可选用 logback `ListAppender` 断言失败路径确实发了 WARN，不阻塞主线交付。

### [S2.9] Tests

`llm-core` — `MemoryCompressorTest`：

- 低于阈值：历史不变，摘要 LLM **零调用**。
- 高于阈值：旧轮次被并入摘要但**原文保留在 `messages()`**；`context()` 呈 `[system 原提示][摘要][tail]`；原 system 提示逐字节不变。
- tail 起点必为 `user`：构造切点落在 `assistant`/`tool` 的输入，断言回退到更早的 `user`。
- 增量：连续两次压缩，断言第二次的调用入参含旧摘要正文 + 被挤出的轮次，且不含更早原文。
- 摘要失败（stub 抛异常）：历史与调用前完全一致，返回 false，不抛。
- 前缀识别：已有摘要能被找到并正确剥离前缀；不含前缀的 system 消息不会被误认。
- 配置校验：`keepMessages < 2`、`maxMessages <= keepMessages + 2`、非正数 → `IllegalArgumentException`。

`llm-core` — `ConversationMemoryTest` 补 `replaceAll` 语义（替换、防御性拷贝、空列表）与 `context()` 视图（无摘要 = 全量、跳过折叠轮次、`withUser` 基于 context）。

`web` — `ChatServiceTest` 补：压缩在构建 payload 之前被调用；压缩失败不影响返回 reply 与历史写入；压缩后 `messages()` / `history()` 仍含全部轮次。

`cli` — 补长会话触发压缩（stub LlmClient），`/history` 输出不含摘要 system 消息。

日志覆盖（见 S2.8）：`llm-core` 不断言日志文本；`web` 可选用 logback `ListAppender` 断言「失败路径发 WARN 且历史不变」「成功路径发 INFO 含 size 前后值」。未到阈值零日志靠 code review 保证，不为此引入测试绑定。

## [S3] Out of Scope

- 按 token 触发（需要 tokenizer 或字符系数估算）；本版只按消息条数。
- 异步 / 后台压缩；本版同步压在请求路径上。
- 持久化 `SessionStore`（Redis/DB）；压缩后的内存形态随会话丢弃。
- 跨会话记忆、向量检索、RAG。
- 单条消息内裁剪（如超长 tool 输出截断）。
- 摘要质量评估 / 多候选摘要。
- 对 HTTP API 契约的任何变更（摘要不进 `/messages`）。

## Tasks

- [x] T1: `ConversationMemory.replaceAll` — acceptance: 单测覆盖替换、防御性拷贝、空列表 (covers: S2.3)
- [x] T2: `MemoryCompressor` 布局规则（结构头 / 摘要 / tail、`user` 切点回退、前缀识别）— acceptance: S2.9 对应单测全绿，不依赖真实 LLM (covers: S2.1, S2.2)
- [x] T3: 增量摘要 prompt 与 `LlmClient` 调用 — acceptance: 二次压缩入参含旧摘要 + 新变旧轮次；prompt 与 S2.4 一致 (covers: S2.4)
- [x] T4: slf4j 接线与压缩日志（`llm-core` 依赖 + `module-info` + `cli` 绑定 + 事件级别）— acceptance: 成功 INFO、失败 WARN（带栈）、跳过 DEBUG；`context` 进日志前缀；未到阈值零日志 (covers: S2.8)
- [x] T5: 配置校验 + web 接入（`agent.memory.*`，`ChatService` 压缩先于 `withUser`）— acceptance: `ChatServiceTest` 覆盖压缩前置与失败不阻断；非法配置启动失败；启动期打出配置 INFO (covers: S2.5, S2.6, S2.7)
- [x] T6: cli 接入（env 配置 + `ChatConsole` 重载）— acceptance: 长会话触发压缩；`/history` 不含摘要；现有命令行为不变 (covers: S2.6)
- [x] T7: README 更新（配置表、压缩行为、日志级别说明）— acceptance: 文档与真实配置键、默认值一致 (depends: T5, T6)
- [x] T8: `./gradlew test` 三模块全绿 + 独立复审 — acceptance: 无回归，S2.9 全覆盖 (depends: T1–T7)

## Journey log

- **提示词拆出到资源文件**：`DEFAULT_INSTRUCTION` 原本是 `MemoryCompressor` 里的 text block，改提示词要重编 Java。改为 `llm-core/src/main/resources/dev/kualu/llm/summarize-instruction.txt`，类初始化时读入。边界划在「措辞 vs 结构」：指令正文（要保什么、什么语气）进文件；`summarizePrompt` 的 `[Previous summary]` / `[Older turns]` 框架留在代码——那是请求装配结构，且中间要插入动态内容，做成模板反而多一层替换机制。若日后要连框架一起外置，再引入模板占位符即可。资源缺失当作打包错误抛 `IllegalStateException`，不做静默回退（静默空指令会静默毁掉摘要质量）。
- **`/history` 与摘要的张力**：S2.6 写"`/history` 行为不变"，S2.9 又要求"`/history` 输出不含摘要 system 消息"。两者不完全一致。落地时按 S2.9 执行——`printHistory` 过滤掉 **`role == system` 且** 内容以 `SUMMARY_PREFIX` 开头的消息，原 system 提示照常显示，命令的形状（列表、序号、preview）不变。只按内容前缀过滤会误伤恰好以该前缀开头的 user/assistant 轮次，所以必须同时限定 `system` 角色。这与 web 侧 `ChatService.history()` 天然滤掉 system 的行为对齐。
- **不变量 1 与 2 的冲突（已知限制）**：原 system 提示若**以** `SUMMARY_PREFIX` 开头，与摘要无法区分，会被当作旧摘要折叠。试过用 `ChatMessage.toolCallId` 做唯一标记来根治，但 `LlmClient` 会把非空 `tool_call_id` 原样写进请求体，system 消息带上它属于非标准字段，可能被后端拒收——为一个需要用户刻意命名才会撞上的边界污染所有请求体，不划算。结论：保留前缀识别，用测试钉住当前行为，把这条例外写进 S2.1 不变量 1。前缀出现在**中间**则安全，已有测试覆盖。
- **摘要返回空文本要当失败**：spec 没写这条。但 LLM 返回空白摘要会把被丢弃的轮次整体静默吞掉，属于外部 API 边界的正确性问题，所以按 S2.7 的失败路径处理（`WARN` + 历史不动）。
- **`LlmClient` 可继承**：测试直接 `extends LlmClient` 覆写 `chat`/`chatStream` 打桩，没有为可测性引入额外的 `Summarizer` 接口——保持 spec 里"不新增抽象"的决定。
- **`llm-core` 的 slf4j 是 `implementation` 不是 `api`**：`module-info` 里是 `requires org.slf4j`（非 `requires transitive`），下游编译期看不到 slf4j 类型。所以 `cli` 需要显式再声明一次 `slf4j-api` 才能在 `Main` 打启动日志。若日后想让库消费者直接编译依赖 slf4j，改成 `api` + `requires transitive` 即可。
- **切点回退会留多于 `keepMessages` 的原文**：回退幅度取决于 turn 边界，通常 ≤ 3 条。这是 spec S2.1-3「宁可多留」的刻意取舍，换来的是 tool 结果永不悬空。副作用是压缩后规模可能短暂超过 `maxMessages`，下一轮会自行纠正——spec 里的「≈」是认真的，不是硬上界。
- **压缩发生在 per-session 锁内**：web 侧 `maybeCompress` 在 `synchronized (session.memory())` 里，摘要 LLM 调用会拉长锁持有时间。与既有「同一会话串行」语义一致，但高峰并发下值得留意。
- **`turns.size() <= keepMessages` 的前置守卫被去掉了**：配置校验已保证 `maxMessages > keepMessages + 2`，而触发条件是 `size() > maxMessages`、头部最多占 2 条，所以 `turns.size() > keepMessages` 恒成立。改为让 `tailStartInTurns <= 0` 统一兜底，少一条不可达分支，也不会走到 `subList` 负数下标。
- **压缩改为非破坏性折叠（2026-11-05 修订）**：初版压缩用 `replaceAll` 原地丢弃旧轮次，但 `/history`、web 历史 API 与 `messages` jsonb 持久化都读 `messages()`——旧记录跟着消失，不符合「历史完整保留、只有发给 LLM 的是压缩上下文」的预期。修订后原文轮次永不删除：`messages()` 是完整历史，摘要标记停在折叠分界点（`[system][已折叠原文][摘要][tail]`），新增 `context()` 产出 `[system][摘要][tail]` 供 `withUser` / `ToolLoop` 拼请求。触发条件同步改为按 `context().size()` 计数（`messages()` 只增不减，按 `size()` 会在长会话每轮误触发）；增量摘要只喂「旧摘要 + 新变旧的轮次」，已折叠原文不再重放。持久化格式不变（摘要仍是 messages 数组里的 system 消息），旧压缩会话 `[system][摘要][tail]` 按「折叠点 = 摘要位置」解读即可兼容。代价是存储随会话线性增长——这正是「保留聊天历史」的题中之义，由会话生命周期兜底。
