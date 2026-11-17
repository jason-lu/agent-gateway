---
feature: content-moderation
status: delivered
updated: 2026-11-17
source: working
---

# Content Moderation（不当言论过滤）

## Report

**What was built** — llm-core 新增 `dev.kualu.llm.moderation`：`NormalizedText`（L0 归一化 + 回指映射：NFKC/繁简/同形异码/分隔符折叠/小写）、`LexiconModerator`（L1：自实现 Aho-Corasick + exact/regex/wildcard 三模式 + 文件 mtime 热加载）、`LlmModerator`（L2：复用 `LlmClient` 结构化分类，800ms 超时降级、连续失败熔断 30s）、`SegmentGate`（流式按句界切段、命中前不外发）、`ModerationPolicy`（严重级 × mode 处置矩阵）、`ContentModerator` 管线门面。web 侧：`ModerationService`（输入/输出门 + 审计）、`moderation_events` 审计表（`V11__moderation_events.sql`，只存掩蔽片段与哈希）、复核 API（`/api/v1/admin/moderation/**`，ADMIN）、SSE `moderation` 事件、`422 content_blocked` 错误体。cli 侧同一管线（默认关，`--no-moderation` 有 WARN）。词表骨架在 `llm-core/src/main/resources/moderation/lexicon.json`（占位词条），支持外置文件热加载；配置项 `agent.moderation.*` 齐备，L2 模型解析与 `AGENT_MEMORY_SUMMARY_MODEL` 同款（flash 档优先）。前端 `sse.ts`/`ChatView.tsx` 兼容 `moderation` 事件。

**Verification** — `./gradlew test` 全绿（llm-core 43+ moderation 单测、cli、web 229+ 含 7 个 moderation 集成用例）：归一化等价类、AC 三模式与位置回指、处置矩阵表驱动、热加载、L2 降级/熔断/超时、流式切段不外发、BLOCK 422 + 占位入史、REDACT 掩蔽后才进模型、输出阻断落库为拒绝语、复核 API 权限与标注回流、flag 灰度只审计不拦截。

## [S1] Problem

`web` / `cli` 的用户消息从 `ChatService.sendMessage()`、`ChatConsole` 原样进入 `ConversationMemory.withUser()` 并发给 LLM；模型回复同样原样回流给用户并落库。全链路**没有任何内容安全检查**：

1. **输入侧**：用户可以提交任意文本（含对话框直输、语音 ASR 转写结果），暴力、危害国家安全、反人类等违规内容会直接进入模型上下文，且会被会话持久化留存。
2. **输出侧**：模型可被诱导生成同类违规内容（越狱、角色扮演、"假设不受限制"话术），生成结果直接回吐给用户。即使输入侧拦住直球提问，输出侧不设防就仍有泄露面。
3. **合规面**：作为面向用户提供信息服务的产品，需符合《网络安全法》《网络信息内容生态治理规定》对内容安全的要求，需要有可举证的审核机制（检测记录、处置动作、可复核）。

目标：建立**输入 + 输出双向、分层检测、分级处置**的内容审核能力，在不大增延迟的前提下拦住明显违规内容，对边界内容留人工复核通道，并形成可审计的处置记录。

## [S2] Design

### [S2.0] 总体架构：三层漏斗

```
                      输入侧                              输出侧
                  （用户消息/ASR 文本）                 （模型生成文本）
                         │                                   │
                         ▼                                   ▼
              ┌─────────────────────┐            ┌─────────────────────┐
              │ L0 归一化预处理      │            │ L0 归一化预处理      │
              │ 全角/繁简/零宽字符   │            │ （同左）             │
              └──────────┬──────────┘            └──────────┬──────────┘
                         ▼                                   ▼
              ┌─────────────────────┐            ┌─────────────────────┐
              │ L1 规则层（同步）    │            │ L1 规则层（同步）    │
              │ 词表 AC 自动机       │            │ 逐段检查（流式）     │
              │ + 变体归一 + 正则    │            │                     │
              └───────┬──────┬──────┘            └───────┬──────┬──────┘
                 命中│      │放行/存疑               命中│      │放行
                      ▼      ▼                          ▼      ▼
                 直接处置  ┌──────────────┐         直接处置  继续输出
                 (BLOCK)  │ L2 LLM 分类   │         (掐断)   (至下一段)
                          │ （异步/限时）  │                  │
                          └───┬──────┬───┘                  │
                        违规  │      │ 合规/存疑              │
                              ▼      ▼                      ▼
                          分级处置  放行/入复核队列   ┌──────────────┐
                                                    │ L3 人工复核   │
                                                    │ 队列 + 标注   │
                                                    └──────────────┘
```

- **L1 是同步快速路径**（目标 P99 < 5ms），拦截明确违规；LLM 分类只对 L1 放行或存疑的文本启用，带超时（默认 800ms），超时降级为 L1 结论。
- **L3 是异步事后通道**：边界样本、`flag` 处置、误杀申诉的落点，标注结果回流词表/阈值。
- **fail 策略**：L2 不可用（超时/异常）时**降级不阻塞**——按 L1 结论处置，仅打 WARN。理由：审核是合规增强，不应因分类器故障阻断全部对话；但 L1 命中**永远生效**，不因 L2 故障放行。

### [S2.1] 违规类目与分级

类目是**封闭枚举**，按内容安全合规口径定义；词表与判例由运营配置，不进源码。

| 类目码 | 含义 | 典型 | 默认严重级 |
|---|---|---|---|
| `VIOLENCE` | 暴力恐怖：宣扬暴力、恐怖主义、极端行为 | 教唆伤害他人、美化恐怖袭击 | HIGH |
| `REACTIONARY` | 危害国家安全与公共利益：煽动颠覆国家政权、破坏国家统一与民族团结、损害国家荣誉利益等（合规口径，依《网络安全法》《网络信息内容生态治理规定》） | 煽动性政治攻击、造谣诽谤国家机关 | HIGH |
| `ANTI_HUMANITY` | 反人类与仇恨：宣扬种族/民族/性别/地域仇恨、歧视、煽动对立，美化反人类行径 | 仇恨言论、群体攻击、美化侵略与屠杀 | HIGH |
| `CRIME` | 违法犯罪：黄赌毒、制贩违禁品、传授犯罪方法 | 制毒教程、赌博引流 | HIGH |
| `FRAUD` | 诈骗与黑产：诈骗话术、钓鱼引流、个人信息买卖 | 杀猪盘话术、买卖实名账号 | MEDIUM |
| `SELF_HARM` | 自伤自杀：教唆、方法传播（**求助与安抚属合规，不得拦截**） | 教唆自杀方法 | MEDIUM |
| `ABUSE` | 辱骂与低俗：人身攻击、脏话 | 骂战、低俗玩笑 | LOW |

**严重级 → 处置动作**：

| 严重级 | 输入侧 | 输出侧 | 是否入复核队列 |
|---|---|---|---|
| HIGH | `BLOCK`：拒绝本轮，回标准提示，不入对话历史 | 掐断生成，替换为标准拒绝语 | 否（证据明确，只留审计） |
| MEDIUM | `BLOCK` 或 `FLAG`（按 `agent.moderation.mode`） | 同左 | 是 |
| LOW | `REDACT`：掩蔽命中片段后继续 | 同左 | 否，仅计数 |

- **`SELF_HARM` 特例**：用户表露自伤倾向属求助信号，**放行并由系统提示引导求助**（如心理援助热线），仅拦截"教唆/方法传播"。此判别在 L2 语义分类中完成，词表层不做过滤（词表无法区分求助与教唆）。
- `REACTIONARY` 类目的词表与判例**由运营/合规侧维护**（外部词表文件，见 S2.5），源码只保留类目枚举与匹配引擎。设计上按合规口径做事后举证与拦截，不做超出合规要求的扩权拦截。

### [S2.2] 检测器实现

**L0 归一化**（纯文本变换，无损可逆不做保存，仅用于检测视图）：

- Unicode NFKC、全角→半角、繁体→简体（OpenCC 或等价映射）
- 去零宽字符、变体选择符、同形异码字符折叠
- 折叠连续分隔符（`我*爱*你`→`我爱你`，`v我50` 类拼音首字母**不做还原**——误伤率高于收益，交 L2）

**L1 词表引擎**（`llm-core` 新模块 `dev.kualu.llm.moderation`）：

```java
package dev.kualu.llm.moderation;

/** 同步快速规则层：词表 + 正则 + 变体归一。 */
public final class LexiconModerator implements TextModerator {
    /** @param lexicon 词表文件（类目 → 词条/正则），启动时加载为 AC 自动机 */
    public LexiconModerator(Path lexicon)

    /** @return 未命中 → empty；命中 → 类目 + 严重级 + 命中位置（用于 REDACT） */
    public Optional<ModerationHit> check(String text)
}
```

- 多模式匹配用 **Aho-Corasick**（纯 Java 实现或 `org.ahocorasick` 依赖，倾向自实现 ~200 行，避免依赖）。
- 词条支持匹配模式：`exact`（归一化后子串）、`regex`（对归一化视图）、`wildcard`（`*` 通配 0–4 字，用于拆字变体：`法*轮*功`）。
- 命中返回**位置区间**，供 `REDACT` 掩蔽（替换成 `***`）。
- 词表热加载：监听文件 mtime，变更后重建自动机（重建期间旧实例继续服务）。

**L2 LLM 分类器**（复用 `LlmClient`，不新增抽象接口）：

```java
package dev.kualu.llm.moderation;

/** 语义层分类：输入文本 → 类目 + 置信度。只对 L1 放行/存疑文本调用。 */
public final class LlmModerator {
    /** @param client 取活的客户端（每次调用现取，跟随运行期 provider 变更，同 MemoryCompressor） */
    public LlmModerator(Supplier<LlmClient> client, Duration timeout)

    /** 非流式单次调用，提示词强制结构化输出（JSON）；超时/解析失败 → Optional.empty()（调用方降级） */
    public Optional<ModerationVerdict> classify(String text, Direction direction)
}
```

- 分类提示词固定为系统提示，输出 schema：`{"category": "NONE|VIOLENCE|...", "severity": "...", "confidence": 0.0-1.0, "reason": "..."}`。
- `confidence` 低于阈值（默认 0.7）→ 视为存疑，入 L3 复核队列而非直接 BLOCK。
- **只分类，不生成回复**，单次调用输入长度截断到 2000 字符（截断可能漏检尾部，这是已知代价；长文本按段分批）。
- **模型选择：flash 档优先**。分类是单轮小任务，延迟与成本敏感，应选用部署清单中的 flash 档小模型（如 `mimo-v2.6-flash`，与 `AGENT_MEMORY_SUMMARY_MODEL` 同档），**不占用用户的聊天模型**。解析方式与 `MemoryCompressor` 完全同款：`llmSettings.clientForModel(provider, model)` 拿不到就退回 `llmSettings.defaultClient()`；客户端按调用现取，运行期换 provider 不需重启。

**输入侧 / 输出侧挂载点**：

| 位置 | 时机 | 代码点 |
|---|---|---|
| 输入-文本 | 用户消息进 `memory.withUser()` **之前** | `ChatService.sendMessage()`、`ChatConsole` |
| 输入-语音 | ASR 转写文本产出后、作为消息入史之前 | `AsrWebSocketHandler` / 转写累积出口 |
| 输出-流式 | **逐段**（按句末标点切段）检查，命中即掐断 SSE | SSE 输出消费链路（`SessionController` / 前端 `sse.ts` 收到 `moderation` 事件即停渲染） |
| 输出-落库 | 完整回复写 `sessionStore.update()` **之前**再全量过一遍 | `ChatService` 回合收尾 |

- **流式是关键取舍**：整段缓冲再发会毁掉流式体验；实现采用**按句界切段的 hold-back**——`SegmentGate` 累积 delta，句末标点处切段，**先检查后外发**（REDACT 掩蔽后发、BLOCK 直接掐断不外发），无句界的尾巴在流结束时过同样的检查。这比"先播后查"更安全，代价只是按句粒度而非按 token 粒度出字。跨句界的词条与 L2 语义违规仍可能已播出——那由落库前的全量检查兜底，**已播出的段不撤回，但不落库、计审计事件**。句切分用标点字符集（。！？；…!?.;\n），不引 NLP 分词。
- 输出侧命中后向客户端发专用事件 `event: moderation`（`{"action":"blocked","category":"..."}`），前端渲染统一拒绝文案；**拒绝文案不透露具体规则与命中词**（防探针）。

### [S2.3] 处置与数据模型

**统一处置结果**：

```java
package dev.kualu.llm.moderation;

public record ModerationHit(
        Category category,      // S2.1 枚举
        Severity severity,      // HIGH / MEDIUM / LOW
        Action action,          // BLOCK / REDACT / FLAG
        String excerpt,         // 命中片段（已掩蔽中间部分，如 法***功），不存全文
        List<Span> spans        // 原文坐标的命中区间（语义层 L2 无区间 → 空表）
) {
    public record Span(int start, int end) {}   // 实现细化：多区间合并重叠后逐个掩蔽
}

public enum Direction { INPUT, OUTPUT }
public enum Action { BLOCK, REDACT, FLAG }
```

**审计表**（Flyway `V11__moderation_events.sql`，Postgres 模式；`memory` 模式下落 `InMemoryModerationEventStore`，进程内留存）：

```sql
create table agent.moderation_events (
    id              bigserial primary key,
    created_at      timestamptz not null default now(),
    owner           text        not null,
    session_id      uuid,
    direction       text        not null,   -- INPUT | OUTPUT
    category        text        not null,
    severity        text        not null,
    action          text        not null,   -- BLOCK | REDACT | FLAG
    detector        text        not null,   -- LEXICON | LLM（实现：有区间→LEXICON）
    excerpt         text,                   -- 掩蔽片段，非全文
    text_sha256     text,                   -- 归一化视图哈希，用于去重/申诉比对
    needs_review    boolean     not null default false,  -- MEDIUM 或 FLAG 的存疑项
    reviewed        boolean     not null default false,
    review_note     text,
    review_override text                   -- CONFIRM（正例）| NONE（误杀）
);

create index agent_moderation_events_owner_created_idx
    on agent.moderation_events (owner, created_at desc);
create index agent_moderation_events_pending_idx
    on agent.moderation_events (created_at desc) where not reviewed;
```

- **隐私最小化**：只存掩蔽片段与哈希，**不存违规原文**（原文含违规内容，留存即扩大风险面）；需要举证时靠哈希与会话内该轮的占位记录对应。
- 被 `BLOCK` 的用户消息**不进 `ConversationMemory`**，该轮以系统占位消息（`【该消息未通过内容安全检查】`）留在历史里供上下文连续；模型侧看不到原始违规文本（防诱导扩散）。
- 被 `REDACT` 的消息以掩蔽后文本入史（模型与用户看到的都是掩蔽版）。
- 输出被掐断时：已播出段不落库，落库为标准拒绝语。

### [S2.4] 对外契约

**HTTP**（web）：

- 被 BLOCK 的轮次返回 `422` + 统一错误体：`{"error":"content_blocked","message":"内容不符合平台规范，请调整后重试"}`（`ApiExceptionHandler` 已有统一错误体形状，新增一个错误码）。
- **SSE** 新增事件：`event: moderation` / `data: {"action":"blocked"|"redacted","category":"..."}`。
- **复核 API**（ADMIN 角色，沿用 `AdminController` 风格）：
  - `GET /api/v1/admin/moderation/events?reviewed=false` — 分页列出待复核
  - `POST /api/v1/admin/moderation/events/{id}/review` — `{"reviewed":true,"note":"...","override":"NONE|CONFIRM"}`；`CONFIRM` 的样本入"正例库"导出（供词表迭代），`NONE` 的入"误杀库"

**CLI**：同一 `TextModerator` 管线，命中时打印 `⚠ 内容未通过安全检查（类目：X）`，不写入历史；`--no-moderation` 调试开关仅本地生效且启动打 WARN。

### [S2.5] 配置

| Web key | Env | 默认 | 说明 |
|---|---|---|---|
| `agent.moderation.enabled` | `AGENT_MODERATION_ENABLED` | `true`（web）/ `false`（cli 默认，可开） | 总开关 |
| `agent.moderation.mode` | `AGENT_MODERATION_MODE` | `block` | `block` = 按严重级处置；`flag` = 只记审计不拦（灰度/调参用） |
| `agent.moderation.lexicon.path` | `AGENT_MODERATION_LEXICON_PATH` | `classpath:moderation/lexicon.json` | 外部词表文件路径，支持热加载 |
| `agent.moderation.llm.enabled` | `AGENT_MODERATION_LLM_ENABLED` | `true` | L2 开关 |
| `agent.moderation.llm.provider` | `AGENT_MODERATION_LLM_PROVIDER` | 空 | 分类用 provider id；空则按模型名模糊解析（同 `AGENT_MEMORY_SUMMARY_PROVIDER`） |
| `agent.moderation.llm.model` | `AGENT_MODERATION_LLM_MODEL` | 空 → 退回 `AGENT_MEMORY_SUMMARY_MODEL` → 退回默认聊天模型 | 分类模型 ID，**优先填 flash 档**（如 `mimo-v2.6-flash`） |
| `agent.moderation.llm.timeout-ms` | `AGENT_MODERATION_LLM_TIMEOUT_MS` | `800` | 超时降级 L1 |

词表文件形状（**运营维护，不进源码**；仓库内只放示例骨架——**首版即按骨架上线，`mode=flag` 灰度**，审计数据回流迭代后再切 `block`）：

```json
{
  "version": 3,
  "categories": {
    "VIOLENCE":  {"severity": "HIGH", "terms": [{"pattern": "…", "type": "exact"}]},
    "REACTIONARY": {"severity": "HIGH", "terms": [{"pattern": "…", "type": "regex"}]},
    "ANTI_HUMANITY": {"severity": "HIGH", "terms": []},
    "CRIME":     {"severity": "HIGH", "terms": []},
    "FRAUD":     {"severity": "MEDIUM", "terms": []},
    "SELF_HARM": {"severity": "MEDIUM", "terms": []},
    "ABUSE":     {"severity": "LOW", "terms": []}
  }
}
```

### [S2.6] 失败语义

| 情况 | 行为 |
|---|---|
| 词表文件缺失/损坏 | 启动**硬失败**（与 JWT secret 同级要求：宁可不起，不可裸奔）；热加载失败保留旧词表并 WARN |
| L2 LLM 超时/异常/解析失败 | **降级**为 L1 结论（多数为放行），打 WARN；连续失败超阈值熔断 30s（期间不调 L2） |
| 审计表写失败 | 处置动作照常执行（BLOCK 不因审计失败而放行），打 WARN |
| `mode=flag` 且命中 | 全部只记审计不拦截，WARN 提示当前为灰度模式 |

### [S2.7] Tests

**单元**（不碰真库）：

- L0 归一化：全角/繁体/零宽/分隔符折叠的等价类样例。
- 词表引擎：exact / regex / wildcard 三模式命中与位置；AC 多模式同时命中取最高严重级；热加载后新词条生效。
- 处置矩阵：类目 × 严重级 × mode → 期望 `Action` 的表驱动测试。
- `LlmModerator`：用可注入 `LlmClient` 子类喂固定 JSON / 畸形 JSON / 超时，断言降级行为。
- 流式切段：句界切分不吞段、命中掐断后不再输出。

**契约**（`WebGatewayIntegrationTest` 扩展）：

- 输入 BLOCK → `422` + 错误体；历史里是占位消息而非原文。
- `REDACT` → 历史与模型 payload 均为掩蔽文本。
- 输出命中 → SSE 收到 `moderation` 事件且拒绝语落库。
- 复核 API：ADMIN 可列出/处置事件；USER 调用得 `403`。
- `mode=flag` 时同输入不拦截但审计有记录。

**回归基准**：固定一组「应拦 / 不应拦」样本集（含 `SELF_HARM` 求助 vs 教唆对、新闻引用 vs 宣扬对、代码/医学语境中的暴力词），作为词表迭代的回归门禁。

### [S2.8] 依赖与模块

- `llm-core` 新增 `dev.kualu.llm.moderation`（`TextModerator`、`LexiconModerator`、`LlmModerator`、`ModerationHit`）；**纯库，无 Spring**。
- `web` 装配 + 挂载点 + 审计表 + 复核 API；`cli` 挂载点。
- 新增依赖：无强制（AC 自动机自实现）；繁简转换若不引 OpenCC 则内置常用映射表（约 2000 对，覆盖高频字），在 Journey log 记录取舍。

## [S3] Out of Scope

- 图片/文件内容审核（仅文本与 ASR 转写文本）。
- 多模态输入（图像理解结果）的审核——留待后续，但挂载点按 `Direction` 抽象已预留。
- 用户信用分、封号、限流等账号级风控处置（本期只做消息级处置 + 审计）。
- 词表运营后台（本期词表是文件 + 复核 API 标注回流）。
- 模型安全护栏（system prompt 加固、越狱对抗训练）——与本特性互补但独立。
- 联邦/第三方内容安全云服务对接（架构上 `TextModerator` 是接口，后续可加实现）。
- 跨会话用户行为分析与预警。

## Tasks

- [x] T1: `llm-core` `dev.kualu.llm.moderation` 骨架（枚举、`ModerationHit`、`TextModerator` 接口、L0 归一化）— acceptance: 单测覆盖归一化等价类 (covers: S2.1, S2.2)
- [x] T2: `LexiconModerator`（AC 自动机 + 三模式匹配 + 热加载）— acceptance: 位置命中正确；热加载生效；表驱动处置矩阵全绿 (covers: S2.2)
- [x] T3: `LlmModerator`（结构化分类 + 超时降级 + 熔断，flash 档模型解析同 `MemoryCompressor`）— acceptance: 畸形/超时均降级不抛出；`summary-model` 同款 fallback 链生效 (covers: S2.2, S2.6)
- [x] T4: 输入侧挂载（`ChatService` + `ChatConsole` + ASR 出口）— acceptance: BLOCK 不入史且返回 422；REDACT 掩蔽入史 (covers: S2.2, S2.3)（ASR 转写经前端回传聊天接口，已由 ChatService 入口覆盖，无需单独钩子）
- [x] T5: 输出侧挂载（流式逐段检查 + 落库前全量检查 + SSE `moderation` 事件）— acceptance: 命中掐断、拒绝语落库 (covers: S2.2, S2.3)
- [x] T6: Flyway `V11__moderation_events.sql` + 审计写入（memory 模式 `InMemoryModerationEventStore`）— acceptance: 表结构与 S2.3 一致；写失败不影响处置 (covers: S2.3, S2.6)
- [x] T7: 复核 API（ADMIN 列表/处置 + 正例/误杀导出）— acceptance: 权限正确；标注可回流 (covers: S2.4)
- [x] T8: 配置项（含 `llm.provider`/`llm.model` flash 档默认链）+ 词表示例骨架 + README 安全注记 — acceptance: 文档与配置键一致；骨架词表外置可热加载；README 写明灰度路径（`flag` → 收集 → 切 `block`） (covers: S2.5)
- [x] T9: 回归样本集 + `./gradlew test` 全绿 + 独立复核 — acceptance: 应拦/不应拦样本集全过 (depends: T1–T8)

## 已决事项（原「待确认」）

- **技术路线**：混合分层（词表快速拦截 + LLM 分类兜底 + 人工复核队列）。已确认。
- **覆盖范围**：输入 + 模型输出双向。已确认。
- **交付形式**：本 spec 文档。已确认。
- **分类用模型**：**flash 档优先**。从部署模型清单里选 flash 小模型（如 `mimo-v2.6-flash`），配置 `agent.moderation.llm.model`（可选配 `provider`）；解析与 `AGENT_MEMORY_SUMMARY_MODEL` 同款（`clientForModel` → 拿不到退回默认聊天模型）。已确认。
- **词表初版来源**：**示例骨架先上线**。不等运营词表——仓库内示例骨架 + `agent.moderation.mode=flag` 灰度跑，审计表收集漏报/误杀，标注回流（复核 API 的正例/误杀导出）迭代词表，稳定后切 `mode=block`。已确认。
- **复核 API 鉴权**：**沿用 ADMIN 角色**，不新增独立 MODERATOR 角色。已确认。
- **`SELF_HARM` 求助豁免**：求助与安抚放行，仅拦教唆与方法传播；判别放 L2 语义层。设计建议，待实现前复核。
- **L2 失败策略**：降级不阻断（按 L1 结论），L1 命中永不放行。设计建议——若合规侧要求 fail-closed，改 `agent.moderation.mode=fail-closed` 一档即可，架构不变。

## 待确认

- （无。三项实现前问题已全部拍板，见上节。）

## Journey log

- **`ModerationHit` 从单 span 改为 `List<Span>`**：exact 与 wildcard 词条会双重复盖同一区域（归一化后 separator 被剥离，wildcard 零间隔匹配 exact 的所有命中点），单 span 会重复掩蔽或漏掩。落地时合并重叠 span、`redact` 从后往前逐段替换成 `***`。语义层（L2）无区间，span 为空表——REDACT 对语义命中不可执行，降级为 FLAG 并写进 `LlmModerator` 的契约注释。
- **流式"先播后查"改为"句界 hold-back"**：spec 原文的"已播出的段不撤回"隐含先播后查，但那样 REDACT 在流式路径上形同虚设（掩不了已播出的字）。改为 `SegmentGate` 按句末标点切段、先查后发，违规段根本不外发；"已播出不撤回"的取舍只留给落库前全量检查（跨句界词条、L2 语义违规）兜底的场景。代价是出字粒度按句而非按 token，中文句短，可接受。
- **ASR 不需要单独钩子**：`AsrWebSocketHandler` 只把转写文本回传浏览器，文本最终走 `POST /messages`——`ChatService` 输入门天然覆盖语音输入。T4 的"ASR 出口"改为验证性结论记入任务注记。
- **审计表加了 `needs_review` / `review_override` 两列**：spec 的矩阵有"是否入复核队列"一列，只靠 `reviewed` 区分不出来（HIGH 命中也要可申诉）。`needs_review` 由 `policy.needsReview(hit)` 推导（MEDIUM 或任何 FLAG 含 L2 存疑），复核 API 的 pending 查询和 confirm/none 导出靠 `review_override`。
- **词表骨架放 llm-core 而非 web**：cli 与 web 共用 `classpath:moderation/lexicon.json`，放 web 的 resources 会让 cli 找不到默认词表。web/cli 都在 classpath 模式运行，跨模块资源可见。
- **`ChatService` 的 moderation 空转路径**：`segmentGate` 在禁用时返回的 `SegmentGate` 构造即 NPE（moderator 为 null）——第一版测试抓到。现在禁用时直接旁路 gate、用裸 sink。教训：可选管线的"空对象"要么真做成空对象，要么在调用点显式旁路，不能只在构造器里 requireNonNull。
- **SSE 测试的中文断言**：`MockHttpServletResponse.getContentAsString()` 默认 ISO-8859-1，中文断言全挂——改成 `new String(getContentAsByteArray(), UTF_8)`。既有 SSE 测试只断言 ASCII 所以从没暴露。
