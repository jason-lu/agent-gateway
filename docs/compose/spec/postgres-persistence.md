---
feature: postgres-persistence
status: delivered
updated: 2026-02-18
source: main
---

# PostgreSQL Persistence

## Report

**What was built** — Users and conversations now persist to PostgreSQL. `agent.users` holds bcrypt credentials and `roles text[]`; `agent.sessions` holds each conversation as one ordered `messages jsonb` array, so a compressed summary resumes exactly where it left off. Flyway owns the schema (`agent` schema, `V1__init.sql`). `JdbcUserDetailsService` authenticates against the database; config-declared users are seeded once (`insert … on conflict do nothing`) and never overwritten afterwards. `PostgresSessionStore` implements the existing `SessionStore` interface with owner-scoped SQL; `InMemorySessionStore` remains for `agent.store=memory`, which touches no database at all. Credentials come only from the environment; the DB URL must use a TLS mode that cannot fall back to plaintext (whitelist, with loopback as the single exception for local/embedded databases). `health` gained an additive `dbUp` field. A failed persist no longer costs the user an already-produced reply.

**Verification** — `./gradlew test` PASS across llm-core, cli, web, including integration tests against a real PostgreSQL via zonky `embedded-postgres` (no Docker required): the real Flyway migrations, owner isolation, restart-equivalent reads, compressed-summary round-trips, insert-if-absent seeding, and an end-to-end HTTP smoke (login → create session → chat → history) over `agent.store=postgres`. Independent review found no blockers; its security finding (a deny-list on `sslmode` let `prefer`/`allow` through) is fixed.

## [S1] Problem

`web` 目前两处状态都是进程内的：

1. **用户** — `AppConfig.userDetailsService` 从 `agent.security.users` 读配置装进 `InMemoryUserDetailsManager`。改用户要改配置、重启；配置里是明文口令。
2. **会话与聊天历史** — `InMemorySessionStore` 是一个 `ConcurrentHashMap`，注释写明"重启丢失"。压缩摘要、tool 轮次、多轮上下文全部随之消失。

系统已经支持多用户（owner 隔离、USER/ADMIN 角色），用户与会话数据本来就应该落库。目标是一次性把这两类状态迁到 PostgreSQL，登录与会话 API 的对外契约不变。

## [S2] Design

### [S2.1] Schema

独立 schema `agent`，两张表。DDL 由 Flyway 版本化管理。

```sql
create schema if not exists agent;

create table agent.users (
    username      text primary key,
    password_hash text        not null,
    roles         text[]      not null default '{USER}',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table agent.sessions (
    id         uuid primary key,
    owner      text        not null references agent.users (username),
    created_at timestamptz not null default now(),
    messages   jsonb       not null default '[]'::jsonb
);

create index agent_sessions_owner_created_idx
    on agent.sessions (owner, created_at desc);
```

- `messages` 存 `ConversationMemory` 的完整快照（含 system 提示与压缩摘要）。压缩后的形态原样落库，重启后接着摘要继续，与内存行为一致——这正是选 JSONB 整包而不是行式的关键收益。
- `owner` 外键指向 `agent.users`。本版不做用户删除，故无需级联策略。
- Flyway 的 `schema history` 表也放在 `agent` schema 下（`spring.flyway.schemas=agent`），便于整体清理。

**消息 JSON 形状**（`ChatMessage` 的序列化契约，必须无损往返）：

```json
[
  {"role": "system",    "content": "..."},
  {"role": "user",      "content": "..."},
  {"role": "assistant", "content": "..."},
  {"role": "tool",      "content": "...", "toolCallId": "call_abc"}
]
```

`toolCallId` 为 `null` 时省略该字段；tool 轮次必须保住 `toolCallId`，否则回放到 LLM 会悬空。

### [S2.2] 连接与配置

| Web key | Env | 示例 / 默认 |
|---|---|---|
| `agent.db.url` | `AGENT_DB_URL` | `jdbc:postgresql://db.example.com:5432/postgres?sslmode=require` |
| `agent.db.username` | `AGENT_DB_USERNAME` | `postgres` |
| `agent.db.password` | `AGENT_DB_PASSWORD` | **无默认，必须来自环境** |
| `agent.store` | `AGENT_STORE` | `postgres`（可选 `memory`） |

- **口令绝不进源码、不进 `application.yml`**。只出现在 `.env`（已 gitignore）与环境变量里。`.env.example` 只列键名与占位符。
- 连接串必须**显式声明 TLS**。白名单 `sslmode=require` / `verify-ca` / `verify-full`；`prefer` / `allow` / `disable` 一律拒绝，因为它们都可能退化成明文口令认证。唯一例外：host 为 loopback（`localhost` / `127.0.0.1` / `::1`）时允许 `disable`——明文出不了机器，本地/嵌入式库靠这条才可用。
- 驱动走 Spring Boot 的 `DataSource` + HikariCP（`spring-boot-starter-jdbc` 引入），不做自建连接池。
- **待确认（实现首日核对）**：用户给了 host/port/user/口令，**未给库名**。默认先用 `postgres` 库 + `agent` schema；若希望独占一个 `agent` database（更干净、备份/限权更好做），用有 `CREATEDB` 权限的账号建库后改 `AGENT_DB_URL` 即可，代码不感知库名。

### [S2.3] 用户持久化与登录

**认证来源从配置换成数据库**，`UserDetailsService` 查 `agent.users`：

```java
select username, password_hash, roles from agent.users where username = ?
```

`roles` 数组直接映射到 Spring 的 `.roles(...)`（存 `USER` / `ADMIN`，不带 `ROLE_` 前缀，与现状一致）。

**种子（bootstrap）**：启动期把 `agent.security.users` 里的用户 **insert-if-absent** 进库：

```sql
insert into agent.users (username, password_hash, roles) values (?, ?, ?)
on conflict (username) do nothing
```

- 明文口令经现有 `PasswordEncoder`（delegating，bcrypt）编码后入库。
- **不覆盖已有行**：首次启动后数据库即为事实来源，手工改过的口令/角色不会被下次启动冲掉。
- 因此「改密码 / 加用户 / 禁用账号」本版**只能走 SQL**——这正是 ADMIN 管理 API 要补的（见 S3）。
- 配置里删掉某个用户**不会**删库里的账号（seed 只增不减）。

`SecurityProperties` 与 `application.yml` 的 `agent.security.users` 结构**保持不变**，语义从「唯一来源」降级为「首次种子」。

### [S2.4] 会话持久化 `PostgresSessionStore`

放在 `dev.kualu.web.session`，实现现有接口（**接口零改动**）：

```java
public interface SessionStore {
    Session create(String owner, String systemPrompt);
    Optional<Session> find(String owner, String sessionId);
    List<Session> listByOwner(String owner);
    boolean delete(String owner, String sessionId);
    Session update(Session session);
}
```

| 方法 | SQL 要点 |
|---|---|
| `create` | `insert into agent.sessions (id, owner, messages) values (?, ?, '[]'::jsonb)`；system 提示作为首条消息写入 `messages` |
| `find` | `select ... where id = ? and owner = ?`；反序列化 `messages` → `ConversationMemory` |
| `listByOwner` | `select ... where owner = ? order by created_at desc`。**注意**：本版取整包而非 `jsonb_array_length`——`SessionStore` 返回 `List<Session>` 而 `Session` 持有完整 `ConversationMemory`，两者不可兼得（见 Journey log） |
| `delete` | `delete where id = ? and owner = ?` |
| `update` | `update agent.sessions set messages = ? where id = ? and owner = ?`，单语句原子 |

- 序列化用 Jackson（web 已有），`ChatMessage` 是 record，无需自定义 adapter。
- `Session` 构造出的 `ConversationMemory` 反序列化后**必须保序**（`List`），并保住 `toolCallId`。
- 并发语义不变：调用方（`ChatService`）仍持有 `synchronized (session.memory())`，`update` 是单条 UPDATE，无跨语句事务需求。
- `listByOwner` 的排序沿用现状（`createdAt` 倒序）。

### [S2.5] 实现选择（postgres vs memory）

按 `agent.store` 显式选择，不用 classpath 猜：

| 取值 | 行为 |
|---|---|
| `postgres`（默认） | 注册 `PostgresSessionStore` + DB 版 `UserDetailsService`；起不来就启动失败 |
| `memory` | 注册 `InMemorySessionStore` + 现有配置版 `UserDetailsService`；启动期打 `WARN` 明确说「未持久化，重启丢数据」 |

- `memory` 保留给本地无库调试与测试，**不作为生产默认**。
- 两种模式的 HTTP 契约完全一致，只是持久性不同。
- `llm-core` 完全不感知存储层，仍是纯库。

### [S2.6] 失败语义

| 情况 | 行为 |
|---|---|
| 启动期连不上库 / Flyway 迁移失败 | **启动失败**，错误信息指向连接串与 `sslmode`（不打印口令） |
| `agent.db.password` 未配置且 `store=postgres` | **启动失败**（与 JWT secret 同等要求，不给不安全默认） |
| 会话途中库写失败（LLM 已成功出回复） | **回复仍返回给用户**，该轮留在进程内存里，打 `WARN`。理由：阻断用户已生成的答案换不回写入；用户拿到回复，运维从日志与健康检查发现 |
| `find` / `listByOwner` 读失败 | 映射为 `500`（现有统一错误体），不泄漏 SQL 与连接串 |
| 用户名密码错误 | `401`，与现状一致 |

核心原则与压缩特性一致：**持久化是可观测性/耐久性路径，不该静默吞掉用户已得到的结果**；但启动期必须硬失败，不能带着半残状态上线。

### [S2.7] 健康检查

`GET /api/v1/health` 扩展（**只增字段，不改已有字段**）：

```json
{ "status": "UP", "llmConfigured": true, "dbUp": true }
```

- `store=memory` 时 `dbUp` 恒为 `true`（无库可探，视为不阻塞）。
- 探活用一句 `select 1`，不做重试风暴。
- 认证面不变：`/api/v1/health` 仍然 public。

### [S2.8] 依赖与迁移

`web` 新增：

```
org.springframework.boot:spring-boot-starter-jdbc      // DataSource + HikariCP + JdbcTemplate
org.postgresql:postgresql                              // runtime
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
```

测试：`org.testcontainers:postgresql` + `org.testcontainers:junit-jupiter`（见 S2.9）。

迁移文件：`web/src/main/resources/db/migration/V1__init.sql`（内容即 S2.1）。后续 schema 演进一律新增 `V2__*.sql`，**不改历史迁移**。

选 Flyway 而不是「启动时幂等 DDL」的理由：`create table if not exists` 做不了列演进，而这个特性之后必然长（用户管理、消息索引）。代价是多一个依赖与一份迁移文件。

### [S2.9] Tests

**单元**（不碰真库）：

- `JdbcUserDetailsService`：命中 / 未命中、`roles` 映射正确。
- 种子逻辑：insert-if-absent——已存在的用户名口令与角色**不被覆盖**；缺失的被补上。
- 消息序列化：`List<ChatMessage>` → JSON → `List<ChatMessage>` 无损往返，含 system / tool（`toolCallId`）/ 压缩摘要三种形态；`toolCallId` 为 null 时 JSON 里省略。
- `PostgresSessionStore` 的 SQL 与映射（对 `JdbcTemplate` 打桩）：`listByOwner` 不取 `messages` 整包、owner 过滤生效、`update` 带 owner 条件。

**集成**（Testcontainers，需 Docker）：

- 建库 → 跑 Flyway → CRUD 一轮：`create` → `find` → `update` → `listByOwner` → `delete`，断言 owner 隔离（B 用户读不到 A 的会话）。
- 重启等价性：写入后**新开一个 store 实例**再读，内容一致（这是「重启不丢」的直接证明）。
- 压缩摘要落库后再读回，摘要 system 消息原样保留。

**契约**：`WebGatewayIntegrationTest` / `SseStreamIntegrationTest` 继续跑 `store=memory`，保证接口行为零回归；另加一条 `store=postgres` 的冒烟（Testcontainers）跑通登录→建会话→发消息。

若环境无 Docker：集成测试用 `@EnabledIf` 跳过并保持单元测试全绿，不降低主干可验证性；实现首日先探测 Docker 再定。

## [S3] Out of Scope

- **ADMIN 用户管理 API**（增删改、改密码、启用/禁用）——本版只有种子 + 登录，改用户走 SQL。
- 用户自助改密码 / 找回密码 / 注册。
- 行式消息表、消息级检索、历史全文搜索。
- 连接池参数调优、读写分离、多副本、备份策略。
- 刷新令牌 / 会话吊销 / JWT 持久化（JWT 仍是无状态的）。
- OAuth2 / OIDC / 多租户。
- 数据加密（列级 / 静态加密）；依赖库本身与传输层 TLS。
- 把 `InMemorySessionStore` 删掉。

## Tasks

- [x] T1: Flyway 迁移 `V1__init.sql` + 连接配置（`agent.db.*`，口令仅来自环境）— acceptance: `store=postgres` 且无口令时启动失败；迁移在真实 PostgreSQL 上跑通 (covers: S2.1, S2.2, S2.8)
- [x] T2: `JdbcUserDetailsService` + 启动期种子（insert-if-absent）— acceptance: 登录走库；已存在用户不被覆盖；单测覆盖 (covers: S2.3)
- [x] T3: `ChatMessage` ↔ JSON 无损序列化 — acceptance: system / tool / 压缩摘要三形态往返无损，`toolCallId` 保住 (covers: S2.1)
- [x] T4: `PostgresSessionStore`（create/find/listByOwner/delete/update）— acceptance: 单测覆盖 SQL 与 owner 过滤 (covers: S2.4)
- [x] T5: `agent.store` 切换与 `memory` 模式 WARN — acceptance: 两种模式 HTTP 契约一致；`memory` 启动期有明确警告 (covers: S2.5)
- [x] T6: 失败语义 + health 扩展（`dbUp`）— acceptance: 写失败仍返回回复且打 WARN；启动期连不上库硬失败；health 只增字段 (covers: S2.6, S2.7)
- [x] T7: 真实 PostgreSQL 集成测试（重启等价性、owner 隔离、摘要落库、HTTP 冒烟）— acceptance: 全绿 (covers: S2.9)
- [x] T8: README / `.env.example` 更新（连接配置、schema、行为差异）— acceptance: 文档与真实配置键一致；`.env.example` 不含真实口令 (depends: T1–T6)
- [x] T9: `./gradlew test` 全绿 + 独立复审 — acceptance: 无回归，S2.9 全覆盖 (depends: T1–T8)

## 已决事项（原「待确认」）

- **数据库名**：用户只给了 host/port/user/口令。代码不感知库名，`AGENT_DB_URL` 里定；本版按 `postgres` 库 + `agent` schema 落地，要独占 `agent` database 时改连接串即可。
- **口令轮换**：该口令已出现在对话里，接入前应换新。新口令只进 `.env`。**仓库内已扫描确认无真实口令/主机**。

## Journey log

- **Testcontainers → zonky embedded-postgres**：本机无 Docker（`docker` 命令不存在），按 spec T7 预留的退路本该跳过集成测试。但那等于迁移 DDL 与 store 真实往返**从未被验证**，风险太高。改用 `io.zonky.test:embedded-postgres`——它下载真 PostgreSQL 二进制在本地起库，不需要 Docker，**在 Windows 上验证可用**。结果是集成测试真的跑了 Flyway、真做了 CRU、真验了 owner 隔离与重启等价。代价是首次跑要下约 30MB 二进制。
- **spec S2.4 自相矛盾**：一边写「接口零改动」，一边要求「`listByOwner` 不取整包」。`SessionStore.listByOwner` 返回 `List<Session>`，而 `Session` 持有完整 `ConversationMemory`、`messageCount()` 从它派生——要在不改接口的前提下给出准确条数是不可能的。落地时选了**冻结接口**，`listByOwner` 取整包。好消息是压缩特性已经把每会话历史压到 `2 + keepMessages` 量级，这个优化本就为时过早。真要拆，需要引入 `SessionSummary` DTO 或计数列，是后续工作不是查询改动。
- **`sslmode` 必须白名单，不能黑名单**：复审发现我原先只拒 `disable`，但 `prefer` / `allow` 同样会退化成明文口令认证，正好违背 S2.2「口令认证明文过网不可接受」。改成白名单 `require` / `verify-ca` / `verify-full`。**额外开一个原则性例外**：host 为 loopback 时允许 `disable`——明文出不了机器，否则本地/嵌入式库根本没法用。这是让冒烟测试能跑通的关键。
- **默认口令会播进公开库**：`application.yml` 的 `AGENT_ADMIN_PASSWORD:change-me` 是既有默认，但以前只进内存，现在会被 bcrypt 编码后**种进公网 PostgreSQL**。删默认会破坏快速上手，折中方案是种子时检测到占位口令就打 `WARN` 点名账号，并在 README 写明必须改。**残余风险**：只警告不阻断，靠运维看到日志。若要更硬，改成 `store=postgres` 时口令必填即可。
- **启动期连库校验是间接的**：Hikari 设了 `initializationFailTimeout(0)`（否则构造就连库，单测没法跑），所以「库连不上」这条硬失败由 **Flyway** 兜着。今天够用，但若哪天 Flyway 被关掉就没有这道闸了。S2.6 要求错误信息指向连接串与 `sslmode`，Flyway 的报错不保证满足——这条留作已知不足。
- **`V1__init.sql` 不写 `create schema`**：靠 `spring.flyway.create-schemas: true` 建 `agent` schema。为自包含起见最后还是把 `create schema if not exists agent;` 加回迁移文件，两处幂等不冲突。
- **外键真的会咬人**：集成测试第一版直接建会话就撞 `sessions_owner_fkey`——`owner` 必须先在 `users` 里。这证明 FK 是活的，也说明**先种子、后建会话**是硬顺序。
