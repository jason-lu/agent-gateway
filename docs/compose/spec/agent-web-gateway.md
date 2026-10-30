---
feature: agent-web-gateway
status: delivered
updated: 2026-02-18
source: feat/agent-web-gateway
---

# Agent Web Gateway

## Report

**What was built** — The repo is now a three-module Gradle build: `llm-core` (HTTP + LLM library, JPMS `dev.kualu.agent`), `cli` (unchanged terminal chat), and `web` (Spring Boot agent gateway). The gateway provides JWT login with USER/ADMIN roles, owner-scoped multi-session chat over REST, SSE streaming (`delta`/`done`/`error`), a pluggable `SessionStore` (in-memory by default), health, and unified error JSON. CLI entry remains `./gradlew run` / `:cli:run`.

**Verification** — `./gradlew test` PASS (llm-core, cli, web). Includes owner-isolation store tests, JWT/login/security tests, REST session/message/error-mapping tests, SSE wire-contract tests, and chat concurrency tests. Independent review + focused re-review confirmed critical findings resolved.

## [S1] Problem

`agent` 目前是单模块 Java 库 + 终端聊天（`Main` → `ChatConsole`）。HTTP/LLM 能力与 CLI 入口绑在同一模块里，无法作为独立服务暴露。需要在保留 CLI 的同时提供 Web 服务，并把可复用的 LLM 核心抽成共享子模块，使同一套对话能力同时服务终端与 HTTP 网关。

## [S2] Design

### [S2.1] Multi-module layout

Gradle 多模块，根项目只做聚合：

| Module | Artifact | Role | JPMS |
|---|---|---|---|
| `llm-core` | `dev.kualu:llm-core` | HTTP + LLM 协议库（现有 `dev.kualu.http` / `dev.kualu.llm`） | keep `module-info.java`，`requires transitive java.net.http`，`requires com.google.gson` |
| `cli` | `dev.kualu:cli` | 终端入口 `Main` + `ChatConsole` | classpath（application） |
| `web` | `dev.kualu:web` | Spring Boot Agent 网关 | classpath（Spring Boot） |

- 根 `settings.gradle.kts`：`include("llm-core", "cli", "web")`。
- 现有源码按包迁移：`http`/`llm` → `llm-core`；`Main`/`ChatConsole` → `cli`。公共 API 包名不变（`dev.kualu.http`、`dev.kualu.llm`），`llm-core` 的 `module-info` 仍为 `dev.kualu.agent`（兼容既有 `requires dev.kualu.agent` 用户）。
- `cli`、`web` 依赖 `project(":llm-core")`。CLI 行为保持与 README 一致（`--stream` / `--no-stream` / `/help` 等）。根工程注册 `run` → `:cli:run`。
- Java toolchain 仍为 25。`llm-core` 继续使用 Gson；`web` 使用 Spring Boot 3.5.x（Web MVC + Security + Validation），JSON 用 Jackson（Spring 默认），不把 Gson 暴露进 HTTP 层 DTO。

### [S2.2] Shared conversation core contract

`web` 不复制协议逻辑，直接复用：

- `LlmClient.fromEnvironment()` / `new LlmClient(LlmConfig)` — full 与 stream 聊天。
- `ConversationMemory` — 多轮历史；`withUser(content)` 构造请求但不写入历史；成功后再 `addUser`/`addAssistant`。
- 错误：`LlmException`（含 `statusCode()` / `responseBody()`）映射为 HTTP 4xx/5xx，不向客户端泄漏 API key。

### [S2.3] SessionStore (pluggable)

```java
package dev.kualu.web.session;

public interface SessionStore {
    Session create(String owner, String systemPrompt); // systemPrompt nullable
    Optional<Session> find(String owner, String sessionId);
    List<Session> listByOwner(String owner);
    boolean delete(String owner, String sessionId);
    Session update(Session session); // persist memory turns after a successful reply
}
```

- 默认实现 `InMemorySessionStore`：`ConcurrentHashMap`，进程内有效，重启丢失。`update` 使用 `computeIfPresent`，删除中的会话不会被并发聊天复活。
- Store 只按 `(owner, sessionId)` 隔离；越权访问返回 404（不区分“不存在”与“无权限”）。
- 未来 Redis/DB 实现只需实现同一接口并注册为 Spring bean；本交付不实现持久化。

### [S2.4] Security (Spring Security)

完整鉴权（登录 / JWT / 角色），stateless JWT：

| Item | Contract |
|---|---|
| Login | `POST /api/v1/auth/login` body `{ "username", "password" }` → `200 { "token", "tokenType":"Bearer", "expiresIn" }`；失败 `401` |
| Token | HS256 JWT，`sub=username`，`roles` claim，`exp` 来自 `agent.security.jwt-ttl`（默认 2h） |
| Secret | `agent.security.jwt-secret`（或 `AGENT_JWT_SECRET`）；未配置或长度 <32 时启动失败 |
| Users | `agent.security.users[]`（username / password / roles）加载到 `InMemoryUserDetailsManager` |
| API auth | 除 `/api/v1/auth/login` 与 `GET /api/v1/health` 外，`/api/v1/**` 要求 `Authorization: Bearer <jwt>` |
| Roles | `USER` 可用会话/对话 API；`ADMIN` 额外 `GET /api/v1/admin/users` |
| CSRF | 无状态 API：disable CSRF |
| Sessions API | 一律以当前认证用户名为 owner，不接受请求体指定 owner |

### [S2.5] HTTP API (Agent gateway)

Base path `/api/v1`。

| Method | Path | Auth | Body / Query | Success |
|---|---|---|---|---|
| GET | `/health` | public | — | `200 { "status":"UP", "llmConfigured": true\|false }` |
| POST | `/auth/login` | public | `{username,password}` | `200` JWT |
| GET | `/sessions` | USER | — | `200 [ {id, owner, createdAt, messageCount} ]` |
| POST | `/sessions` | USER | `{systemPrompt?}` | `201` session meta |
| GET | `/sessions/{id}` | USER | — | `200` session meta |
| DELETE | `/sessions/{id}` | USER | — | `204`；无权限/不存在 `404` |
| GET | `/sessions/{id}/messages` | USER | — | `200 [ {role, content} ]` |
| POST | `/sessions/{id}/messages` | USER | `{content}` | `200 { reply }` 完整回复 |
| POST | `/sessions/{id}/messages/stream` | USER | `{content}` | `200 text/event-stream` SSE |

SSE event contract：

- `event: delta` / `data: {"text":"..."}` — 增量片段（可多次）
- `event: done` / `data: {"reply":"..."}` — 结束，含完整回复
- `event: error` / `data: {"message":"..."}` — 失败；连接随后关闭

错误响应统一：

```json
{ "status": 404, "error": "Not Found", "message": "session not found" }
```

映射：校验失败/非法 JSON `400`，未认证 `401`，权限不足 `403`，session 越权/不存在 `404`，上游 LLM `502`（不透传 key），LLM 未配置 `503`，其它 `500`（message 固定为 `internal error`，不泄漏内部异常）。

### [S2.6] Chat service

`web` 内 `ChatService`：

1. `find` session（owner 隔离）。
2. 在 `synchronized (session.memory())` 内执行 `memory.withUser(content)` → `LlmClient.chat` 或 `chatStream`。
3. 成功后 `addUser` + `addAssistant` 并 `SessionStore.update`。
4. 失败不写历史（与库语义一致）。
5. 同一会话的并发请求按 memory 锁串行，避免 `ConversationMemory`（非线程安全）竞态。

### [S2.7] Configuration

`web/src/main/resources/application.yml` + env override：

| Key | Env | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` |
| `llm.base-url` | `LLM_BASE_URL` / `BASE_URL` | empty |
| `llm.api-key` | `LLM_API_KEY` / `API_KEY` | empty |
| `llm.model` | `LLM_MODEL` / `MODEL` | empty |
| `agent.security.jwt-secret` | `AGENT_JWT_SECRET` | **no insecure default** |
| `agent.security.jwt-ttl` | `AGENT_JWT_TTL` | `2h` |
| `agent.security.users` | — | file config（示例 admin，文档要求改密码） |

JWT secret 缺失或过短：Web 启动失败并打印明确错误。LLM 配置缺失：`health.llmConfigured=false`，聊天 API 返回 `503`。

### [S2.8] CLI compatibility

`cli` 模块保留现有参数与命令：

- `./gradlew :cli:run --args="..."` 与 `./gradlew run` 行为等同旧 `Main`（迁移源码与 master 逐字节一致）。
- 测试仍覆盖 `ChatConsole` 命令解析与 `LlmClient` 协议。

### [S2.9] Tests

- `llm-core`：迁移既有测试并保持绿。
- `cli`：迁移 `ChatConsoleTest`。
- `web`：
  - `SessionStore` / `InMemorySessionStore` owner 隔离与 delete/update。
  - `ChatService` 成功写入历史、失败不写入、并发串行、delete 不复活。
  - MockMvc：health public、login issue JWT、401/403 统一错误体、400 非法 JSON、错用户 404、SSE 事件序列（delta*/done 与 error）。
  - Security：USER 无 ADMIN 权限；ADMIN 可访问 admin API。

## [S3] Out of Scope

- Redis/DB 等持久化 `SessionStore` 实现（只保留接口）。
- OAuth2 / OIDC / 多租户 / API key 网关。
- WebSocket 双向聊天；仅 SSE。
- OpenAPI 生成、指标 Prometheus、分布式追踪。
- 对 LLM 协议（tool_calls 完整流）的扩展。
- 生产级用户管理 UI / 注册。

## Tasks

- [x] T1: 将仓库拆为 `llm-core` / `cli` / `web` 多模块并迁移现有源码与测试 — acceptance: `./gradlew test` 三模块全绿，`llm-core` 仍导出 `dev.kualu.http`/`dev.kualu.llm` 且 `module-info` 为 `dev.kualu.agent` (covers: S2.1, S2.8)
- [x] T2: 实现 `SessionStore` + `InMemorySessionStore` + `Session` — acceptance: 单测证明 owner 隔离与 delete/update 行为 (covers: S2.3)
- [x] T3: 实现 Spring Security JWT 登录与角色保护 — acceptance: login 返回 Bearer JWT；无 token 401；USER 访问 admin 403 (covers: S2.4)
- [x] T4: 实现 REST 会话与消息 API（含错误映射）— acceptance: 建会话/发消息/列历史/删会话符合 S2.5；LLM 失败 502；未配置 503 (covers: S2.5, S2.6, S2.7)
- [x] T5: 实现 SSE 流式消息 API — acceptance: mock 下 stream 依次发出 delta* 与 done；异常发 error (covers: S2.5, S2.6)
- [x] T6: 更新 README（模块结构、Web 启动、API 与安全配置示例）— acceptance: 文档与真实端点/配置键一致 (depends: T4, T5)

## Journey log

- SSE 端到端测试不要用 `asyncDispatch`：`OncePerRequestFilter` 默认不在 async dispatch 上执行，JWT 会丢失导致 403。用 `mvcResult.getAsyncResult()` 等 emitter 完成后直接读 `MockHttpServletResponse` 即可。
- Java `HttpClient` + Tomcat `SseEmitter` 在测试里容易因 chunked 提前 close 抛 EOF；MockMvc 异步更稳。
- `ConversationMemory` 非线程安全：在 web 层用 per-session lock（`synchronized (memory)`）串行化，而不是改库语义。
