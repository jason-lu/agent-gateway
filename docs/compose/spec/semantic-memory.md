---
feature: semantic-memory
status: delivered
updated: 2026-11-18
source: main
---

# Semantic Memory（跨会话语义记忆）

## Report

**What was built** — 跨会话语义记忆全链路：`llm-core` 新增 `HardIdentifierDetector`（确定性硬标识符检测，fail-closed）、`UserProfile`/`MemoryEntry` 值类型、`MemoryAssembler`（【用户记忆】块组装 + bigram top-K + 注入前复扫）、`MemoryPolicy`（memory-policy.txt 请求期策略）；`ToolLoop` 新增 request-only preamble（永不入 memory）。`web` 新增 `MemoryStore` SPI（InMemory/Postgres，硬删除）、`MemoryTools`（memory_save / memory_forget / profile_set，写入前过闸门）、`SemanticMemory`（preamble 装配 + 复扫清理）、`MemoryController`（GET/PATCH/DELETE /api/v1/memories、POST clear、GET/PUT /api/v1/profile，编辑同样过闸门）、`SemanticMemoryProperties`（agent.memory.semantic.*）；`ChatService` 按 owner 挂记忆工具并注入 preamble。前端设置新增「记忆」面板（档案卡 + 条目列表 + 编辑/删除/清空）。Flyway `V12__semantic_memory.sql`。

**Verification** — `./gradlew test` PASS（llm-core/cli/web）；前端 `tsc -b` 与 `vite build` 通过。浏览器冒烟（playwright + Edge）：登录 → 设置 → 记忆面板渲染正确，档案保存成功（「个人档案已保存」），城市填「杭州西湖区」被闸门拒绝并显示隐私提示。Moderation 两组集成测试显式关闭语义记忆（其 LLM mock 只桩了 chat() 路径，正交特性）。

## [S1] Problem

工作记忆已经落地：`ConversationMemory` 保完整原文，`MemoryCompressor` 把旧轮次折叠进 `【历史对话摘要】`，使单会话 payload 有界。但它是**单会话**的——新开会话后助手什么都不记得，用户每次都要重复自我介绍（称呼、技术栈、文风偏好）。

需要补上**语义记忆**：跨会话稳定存在的事实、偏好与个人档案。约束来自用户已拍板的隐私口径：

1. **个人档案（可记录，白名单制）**：仅限称呼/昵称、性别（男/女/不愿透露）、生活城市（仅到市级）。来源必须是用户明确声明或经确认；允许注入模型上下文。
2. **硬标识符（永不记录）**：手机号、邮箱、证件号、银行卡、账号口令、精确住址（区/街道/门牌）。不落库、不进摘要、不进模型上下文；写入前拦截并告知用户；召回前二次扫描做纵深防御。
3. 白名单之外的一切身份信息按硬标识符处理；档案类信息禁止从闲聊静默抓取（候选识别必须走确认）。

因此**不需要**加密存储与密钥管理——含硬标识符的内容根本不入库。

## [S2] Design

### [S2.1] 总体形态

```text
写入：对话轮内（ToolLoop）
  模型判定值得记 ──► memory_save / profile_set 工具
                        │
                        ▼
                 HardIdentifierDetector 闸门 ──命中──► 拒绝并返回策略文案（告知用户）
                        │ 未命中
                        ▼
                    MemoryStore（Postgres / InMemory）

召回：每轮请求装配时（不落 ConversationMemory）
  MemoryAssembler ──► 【用户记忆】system 消息（request-only preamble）
       │                    │
       │                    └─► 与 TOOL_POLICY 同位插入 payload，永不持久化
       └─ PII 复扫（US-15）：命中条目剔除 + 清理 + WARN

管理：REST（owner 隔离） + 前端「记忆」面板
  查看 / 改 / 删 / 一键清空（硬删除）
```

要点：

- **一个写入机制**（工具调用）覆盖自动与显式两条故事线：系统指令告诉模型「学到稳定事实就调 `memory_save` 并简短告知用户记了什么」，用户说「记住 X」同样自然路由到工具。模型合规性不足时，第二批再补 flash 事后抽取（见 S3）。
- **注入与工具策略同款**：`ToolLoop` 已有 request-only 的 `TOOL_POLICY`（永不入 memory）；`【用户记忆】` 块沿同一机制插入，因此 `/history`、历史 API、持久化看到的仍是纯对话。
- **档案与条目分治**：档案是固定字段（可被稳定注入、可被得体使用），条目是自由文本（事实/偏好）。两者的闸门、写入工具、管理界面都不同。

### [S2.2] 隐私口径的落点

| 口径 | 落点 | 任务 |
|---|---|---|
| 硬标识符永不记录 | `HardIdentifierDetector` 挂在 `memory_save` / `profile_set` 写入前 | T1、T5 |
| 白名单外按硬标识符处理 | `profile_set` 只接受 `nickname/gender/city` 三个字段；`memory_save` 的自由文本只收 fact/preference | T5 |
| 档案来源必须声明或确认 | 模型策略（`memory-policy.txt`）：仅当用户明确表达时才调 `profile_set`，识别到候选先问再存 | T5 |
| 粒度封顶（城市只到市级） | `profile_set` 校验：city 含 区/县/街道/镇/乡/路/街/号/室 等细化词即拒绝 | T5 |
| PII 不给模型看 | 不入库 + 注入前复扫兜底 | T1、T3 |
| 「这段别记」优先级最高 | 模型策略：该轮不调任何记忆工具 | T5 |
| 清除后不可复活 | 一律硬删除（无软删、无归档态） | T2、T7 |

### [S2.3] 数据模型（Flyway `V12__semantic_memory.sql`，`agent` schema）

```sql
create table agent.user_profile (
    owner       text primary key,
    nickname    text,
    gender      text check (gender in ('male', 'female', 'unspecified')),
    city        text,
    updated_at  timestamptz not null default now()
);

create table agent.memory_entries (
    id                uuid primary key default gen_random_uuid(),
    owner             text not null,
    kind              text not null check (kind in ('fact', 'preference')),
    content           text not null,
    source            text not null check (source in ('explicit', 'auto', 'confirm')),
    source_session_id text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index memory_entries_owner_idx on agent.memory_entries (owner, updated_at desc);
```

- `user_profile` 一行一用户，**upsert** 语义（profile 字段更新覆盖旧值）。
- `memory_entries` 只存 fact / preference 两类；档案**不进**这张表（白名单字段走 `user_profile`，避免自由文本混入身份信息）。
- **硬删除**：`delete` / `clear` 是物理删除，没有 `status` 软删列——US-12/14 要求清除后不得从任何路径复活。
- `source`：`explicit`（用户显式下令）/ `auto`（模型自主判断）/ `confirm`（候选经用户确认）。保留来源是为了面板可见（US-11）与日后审计，不参与行为分支。
- 约束上限：`content ≤ 500` 字符、`nickname ≤ 32` 字符，服务层校验。

### [S2.4] `MemoryStore` SPI 与实现（`web`，包 `dev.kualu.web.memory`）

与 `SessionStore` 同风格：接口 + `InMemory` + `Postgres` 双实现，`agent.store=memory` 轻部署照常工作。

```java
package dev.kualu.web.memory;

public interface MemoryStore {

    record Profile(String nickname, String gender, String city) {}
    record Entry(UUID id, String owner, String kind, String content, String source,
                  String sourceSessionId, Instant createdAt, Instant updatedAt) {}

    Optional<Profile> findProfile(String owner);
    void saveProfile(String owner, Profile profile);          // upsert

    List<Entry> listEntries(String owner);                    // updated_at desc
    Optional<Entry> findEntry(String owner, UUID id);
    Entry saveEntry(Entry entry);                             // id == null 插入，否则更新 content
    boolean deleteEntry(String owner, UUID id);               // 硬删除
    void clear(String owner);                                 // profile + entries 全清
}
```

- owner 一律参数传入、SQL 全部带 `where owner = ?`（owner 隔离，US-14）。
- `PostgresMemoryStore` 复用现有 `DataSource`；`clear` 在单事务内删两张表。
- 值类型放 `web`（只有 web 需要持久化）；纯逻辑（检测器、组装器）放 `llm-core`（见 S2.5/S2.7），日后 cli 可复用。

### [S2.5] 写入路径：记忆工具 + PII 闸门

**`HardIdentifierDetector`（`llm-core`，包 `dev.kualu.llm.memory`，纯函数、无依赖）**

```java
public final class HardIdentifierDetector {
    public record Hit(String category) {}   // phone / email / id-card / bank-card / address / unknown
    public Optional<Hit> scan(String text);
}
```

- 模式（确定性、fail-closed）：CN 手机号、邮箱、18 位身份证（校验位）、银行卡（Luhn，13–19 位）、精确住址（数字段 + 区/街道/路/号/室 等后缀词）。
- `unknown` 档：命中高危组合但无法归类时同样按硬标识符处理（宁可错杀，隐私方向的错误必须保守）。
- **日志绝不带原文**，只记 `hit=<category>`。

**记忆工具（`web`，包 `dev.kualu.web.memory`；在 `ChatService.toolsFor(owner)` 按 owner 构造，非静态 bean——`AgentTool.execute` 没有 owner 参数）**

| 工具 | 参数 | 行为 |
|---|---|---|
| `memory_save` | `kind` (fact/preference), `content`, `id?` | 闸门扫描通过 → 入库/更新；命中 → **不入库**，返回策略文案供模型转述给用户 |
| `memory_forget` | `id` 或 `query` | 按 id 硬删；query 时删除 content 匹配的条目并回报删了几条 |
| `profile_set` | `field` (nickname/gender/city), `value` | 闸门 + 粒度校验通过 → upsert 档案字段；否则返回拒绝原因 |

- 模型策略文本放资源 `llm-core/src/main/resources/dev/kualu/llm/memory/memory-policy.txt`（与 `tool-policy.txt`、`summarize-instruction.txt` 同边界：措辞在文件、装配在代码）：何时记、何时问、何时绝不记（硬标识符、「这段别记」、未确认的档案候选）。
- 工具失败（校验拒绝、存储异常）一律以**结果文本**返回给模型（`AgentTool` 契约），不抛异常、不阻断对话。

### [S2.6] 召回路径：请求期注入

**`MemoryAssembler`（`llm-core`，包 `dev.kualu.llm.memory`）**

```java
public final class MemoryAssembler {
    /** 组装【用户记忆】system 文本；无内容时返回空字符串。 */
    public String assemble(String owner, String userContent);
}
```

组装规则：

1. **档案恒注入**（若已设置）：`- 称呼：…` / `- 性别：…` / `- 生活城市：…`。
2. **条目取 top-K**：与当前 `userContent` 做二元组（bigram）重合度打分排序，取前 `max-inject` 条；条目总数 ≤ K 时全量注入。无向量库、无 embedding——首版用词面相关性，够用且零依赖。
3. **PII 复扫（US-15 纵深防御）**：每条注入前过 `HardIdentifierDetector`；命中即**剔除该条 + `MemoryStore.deleteEntry` 清理 + WARN**，然后继续组装其余条目。
4. 空记忆返回空串，调用方跳过注入。

**注入位置**（`ToolLoop` 小扩展，向后兼容）

`ToolLoop.run` 现在的 payload 装配为 `[TOOL_POLICY system][context()…][user]`。新增可选参数：

```java
/** request-only 前导 system 消息（如【用户记忆】、memory-policy），插入 TOOL_POLICY 之后，永不写入 memory。 */
public ToolLoop(LlmClient client, Supplier<List<AgentTool>> tools,
                Supplier<List<ChatMessage>> requestPreamble)
```

- 旧构造器行为不变（前导为空）。
- `ChatService` 传入 `() -> preamble(owner, userContent)`：`[system memory-policy][system 【用户记忆】…]`（记忆关闭或为空时省略对应消息）。
- **永不持久化**：`ConversationMemory.messages()` 不含记忆块，`/history`、rawHistory、`PostgresSessionStore` 零改动。

### [S2.7] 管理 API 与前端

REST（`web`，owner 隔离，风格同 `SessionController`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/memories` | `{ profile, entries[] }`（US-11 查看） |
| PATCH | `/api/v1/memories/{id}` | 改 content（US-12；走同一闸门） |
| DELETE | `/api/v1/memories/{id}` | 硬删单条（US-12/13） |
| POST | `/api/v1/memories/clear` | 档案 + 条目全清（US-12/14） |
| GET / PUT | `/api/v1/profile` | 读/写档案三字段（US-4/11/12；PUT 走闸门与粒度校验） |

- 所有路由以 JWT owner 为界，管理端**没有**查看用户记忆的接口（US-14）。
- 前端：设置区新增「记忆」面板——档案卡（称呼/性别/城市，可编辑）+ 条目列表（内容、类型、来源、时间；行内编辑/删除）+ 「清空全部记忆」二次确认。

### [S2.8] 配置

| key | env | default | 说明 |
|---|---|---|---|
| `agent.memory.semantic.enabled` | `AGENT_MEMORY_SEMANTIC_ENABLED` | `true` | 总开关；关闭时不挂记忆工具、不注入 |
| `agent.memory.semantic.max-inject` | `AGENT_MEMORY_SEMANTIC_MAX_INJECT` | `8` | 单次注入条目上限（档案不计入） |

- PII 闸门**没有开关**：启用语义记忆即强制执行；不存在「关闭检测」的配置。
- 配置非法（`max-inject < 1`）拒绝启动，与 `agent.memory.*` 同款启动期校验；启动打一条 `INFO semantic-memory configured: …`。

### [S2.9] 失败语义与日志

**失败语义**

| 故障 | 行为 |
|---|---|
| 记忆写入失败（DB 异常） | 工具返回错误文本给模型；对话不中断、不重试 |
| 注入组装失败 | 跳过注入（无记忆块），WARN，对话不中断 |
| PII 复扫命中 | 剔除 + 硬删除 + WARN，对话不中断 |
| `agent.store=memory` | `InMemoryMemoryStore`，重启丢记忆；启动 WARN 已有先例 |

**日志**（logger 名 `dev.kualu.llm.memory` / `dev.kualu.web.memory`，前缀 `semantic-memory[…]`）

| 事件 | 级别 | 内容 |
|---|---|---|
| 写入成功 | INFO | `saved kind=fact id=… source=explicit`（**不记 content**） |
| 闸门拦截 | WARN | `blocked hit=phone`（**不记原文**） |
| 复扫清理 | WARN | `rescan-cleaned id=… hit=email` |
| 注入组装失败 | WARN + 栈 | `assemble failed; memory block skipped` |
| 配置加载 | INFO | 启动期一条 |

### [S2.10] Tests

- `HardIdentifierDetectorTest`（llm-core）：五类命中各自的正例/反例；身份证校验位、银行卡 Luhn；普通技术文本零误伤；`unknown` fail-closed。
- `MemoryStoreTest`（web）：InMemory 与 Postgres（Testcontainers，`SharedPostgres`）跑同一契约——CRUD、owner 隔离、upsert、`clear` 硬删除后查无残留。
- `MemoryAssemblerTest`（llm-core）：组装布局（档案在前、条目在后）；top-K 排序；条目超上限截断；复扫命中剔除并回调清理；空记忆返回空串。
- `MemoryToolsTest`（web）：save/forget/profile_set 正常路径；闸门命中拒绝且**验证未写库**；city 粒度拒绝（「杭州西湖区」拒绝、「杭州」通过）；工具错误以结果文本返回。
- `ToolLoopPreambleTest`（llm-core）：前导 system 插入位置正确且**不进 memory**；旧构造器行为不变。
- 集成（web，`WebGatewayIntegrationTest` 风格）：会话中说「我在杭州」→ 档案落库 → 新会话 payload 含 `【用户记忆】` 且内容正确；说手机号 → 未落库且助手告知；PATCH/DELETE 后下一回合注入立即变化；clear 后注入为空；`/history` 永不含记忆块。

## [S3] Out of Scope

- **向量检索 / embedding / RAG**：首版词面相关性（bigram 重合度），语料规模小，够用。
- **加密存储与密钥管理**：两档口径下硬标识符不入库，无需加密链路。
- **情景记忆**（对话轨迹检索、跨会话历史回顾）：独立特性。
- **flash 事后抽取**（自动候选提取后确认）：模型工具合规性不足时的第二批增强，先观察 `memory_save` 调用质量。
- **cli 接入**：本期只接 web；`llm-core` 的检测器/组装器已可复用，cli 后接。
- **管理端查看用户记忆**：US-14 明确禁止，不做成后台功能。
- **跨用户记忆共享 / 团队记忆**。

## Tasks

- [x] T1: `HardIdentifierDetector`（llm-core）— acceptance: 五类模式正反例全绿；fail-closed 有断言 (covers: S2.5, US-5)
- [x] T2: Flyway `V12__semantic_memory.sql` + `MemoryStore` SPI + InMemory/Postgres — acceptance: 双实现同契约测试绿；owner 隔离与硬删除有断言 (covers: S2.3, S2.4)
- [x] T3: `MemoryAssembler`（组装 + top-K + PII 复扫清理）— acceptance: S2.10 对应单测全绿 (covers: S2.6, US-8/10/15)
- [x] T4: `ToolLoop` requestPreamble 扩展（向后兼容）— acceptance: 老构造器零回归；前导不进 memory (covers: S2.6)
- [x] T5: 记忆工具 `memory_save` / `memory_forget` / `profile_set` + `memory-policy.txt` — acceptance: 闸门拒绝不落库；city 粒度拒绝；工具失败返回结果文本 (covers: S2.5, US-1/2/3/4/5/6/7)
- [x] T6: `ChatService` 接线（toolsFor 挂工具、preamble 注入）+ 集成测试 — acceptance: 新会话召回生效；`/history` 不含记忆块 (covers: S2.6, US-8/9)
- [x] T7: 管理 REST（memories + profile，含 clear）— acceptance: owner 隔离；改/删立即影响下一回合注入 (covers: S2.7, US-11/12/13/14)
- [x] T8: 前端「记忆」面板 — acceptance: 档案卡可编辑、条目可改可删、清空有二次确认 (covers: US-11/12)

## Journey log

- 2026-11-17 — 需求讨论：user story 15 条定稿（写入/召回/管理/边界四组）。隐私口径从「PII 一刀切不记」修订为**两档划分**：个人档案（称呼/性别/城市）白名单可记，硬标识符永不记录；模型上下文层面「PII 完全不给模型看」。两个口径问题用户已拍板（严格不记 + PII 不进模型），两档划分为对「称呼/性别/生活城市应被记住」的修正案，用户认可。
- 2026-11-17 — 本方案（draft）产出，待实施。
- 2026-11-18 — T1–T8 全部实施完成。相对 spec 的落地偏差（均为小项）：(1) 值类型 `UserProfile`/`MemoryEntry` 上移 `llm-core`（组装器纯逻辑复用所需，spec 原写在 web）；(2) 复扫清理日志记 `rescan-cleaned id=…`，不含 hit 分类（purge 传递 UUID 即可，原文永不入日志）；(3) `InMemoryMemoryStore`/`PostgresMemoryStore` 共用 `Timestamps.next()` 严格单调时间戳——同一时钟 tick 内两次写入会让「最近更新排最前」变得不确定，这是真实缺陷而非仅测试问题；(4) Moderation 集成测试显式设 `agent.memory.semantic.enabled=false`（其 LLM mock 只桩 `chat()`，记忆工具挂上后走 `complete()` 路径返回 null）；(5) 检测器 fail-closed 边上对纯时间戳数字串（YYYYMMDD 前缀）豁免，避免误伤。
- 2026-11-18 — 交付前评审（自查 + 独立评审）修复：`saveEntry` 跨 owner 的 id 不再作为插入键（插入恒用新 UUID——杜绝覆盖他人条目与复活已删条目，双实现语义对齐）；城市粒度按 spec S2.2 词表（区/县/街道/镇/乡/路/街/号/室/村）在 `profile_set` 与 REST 两处独立校验，不再依赖检测器兜底；凭证检测支持「是/为/is」无分隔写法；单分隔手机号（138-12345678）识别；地址检测补街/镇后缀与裸路名（良渚街道/长安镇/文一西路）；`preamble` 组装失败只跳过注入不阻断对话（S2.9）；档案复扫改为硬删除整行（新增 `MemoryStore.deleteProfile`）；工具成功回执不再回显 content。已知取舍：城市词表会误伤「景德镇/新乡」等带细化词的真城市名（可接受的保守方向）；「密码是安全的核心」类讨论性文本会被凭证规则误拦（fail-closed）。
