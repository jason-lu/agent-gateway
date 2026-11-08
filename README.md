# agent

A modular Java LLM toolkit: shared core library, interactive CLI chat, and a Spring Boot agent gateway.

- **`llm-core`** — HTTP client + OpenAI-style `chat/completions` (full or SSE streaming)
- **`cli`** — terminal chat with multi-turn memory (`--stream` / `--no-stream`)
- **`web`** — Spring Boot gateway: JWT auth, multi-session chat, REST + SSE

## Requirements

- Java 25+
- Gradle (wrapper included)

## Modules

```
agent/
  llm-core/          # library: dev.kualu.http + dev.kualu.llm (JPMS: dev.kualu.agent)
  cli/               # terminal entry: dev.kualu.Main
  web/               # Spring Boot web gateway
  docs/compose/spec/ # feature specs
```

`llm-core` keeps `module-info.java` (`dev.kualu.agent`) for library consumers.
`cli` and `web` run on the classpath.

## Configuration

Credentials are read from **environment variables first**, then from a `.env` file in the project root (CLI / `LlmConfig.fromEnvironment`), and from Spring properties for the web module.

For the web module, an admin can override the LLM upstream at runtime (管理 → LLM 上游, or `GET/PUT/DELETE /api/v1/admin/llm-settings`): admin values win **per field** over the environment config above, and any field left unset falls back to it. Changes apply to the next turn without a restart; the CLI keeps using the environment only.

| Setting | Primary | Alias | Web property |
|---|---|---|---|
| Base URL | `LLM_BASE_URL` | `BASE_URL` | `llm.base-url` |
| API key | `LLM_API_KEY` | `API_KEY` | `llm.api-key` |
| Model | `LLM_MODEL` | `MODEL` | `llm.model` |
| JWT secret | `AGENT_JWT_SECRET` | — | `agent.security.jwt-secret` (min 32 chars, required to start web) |
| JWT TTL | `AGENT_JWT_TTL` | — | `agent.security.jwt-ttl` (default `2h`, access-token lifetime) |
| Idle timeout | `AGENT_REFRESH_IDLE_TIMEOUT` | — | `agent.security.refresh-idle-timeout` (default `24h`) |
| Admin user | `AGENT_ADMIN_USERNAME` / `AGENT_ADMIN_PASSWORD` | — | `agent.security.users` (first-start seed only) |
| Persistence backend | `AGENT_STORE` | — | `agent.store` (default `postgres`, or `memory`) |
| Database URL | `AGENT_DB_URL` | — | `agent.db.url` (must set `sslmode`) |
| Database user | `AGENT_DB_USERNAME` | — | `agent.db.username` (default `postgres`) |
| Database password | `AGENT_DB_PASSWORD` | — | `agent.db.password` (**no default, never in source**) |
| Memory compression | `AGENT_MEMORY_ENABLED` | — | `agent.memory.enabled` (default `true`) |
| Compression threshold | `AGENT_MEMORY_MAX_MESSAGES` | — | `agent.memory.max-messages` (default `20`) |
| Turns kept verbatim | `AGENT_MEMORY_KEEP_MESSAGES` | — | `agent.memory.keep-messages` (default `8`) |
| Summary length cap | `AGENT_MEMORY_MAX_SUMMARY_CHARS` | — | `agent.memory.max-summary-chars` (default `4000`) |
| Summary instruction | `AGENT_MEMORY_INSTRUCTION` | — | `agent.memory.instruction` (default: `summarize-instruction.txt`) |
| Exec tool | `AGENT_EXEC_ENABLED` | — | `agent.tools.exec.enabled` (default `true`) |
| Exec timeout | `AGENT_EXEC_TIMEOUT` | — | `agent.tools.exec.timeout-seconds` (default `60`) |
| Exec output limit | `AGENT_EXEC_OUTPUT_LIMIT` | — | `agent.tools.exec.output-limit` (default `8000`) |
| Exec working dir | `AGENT_EXEC_WORKDIR` | — | `agent.tools.exec.workdir` (default: the process working directory) |
| MCP client | `AGENT_MCP_ENABLED` | — | `agent.tools.mcp.enabled` (default `true`) |
| MCP config file | `AGENT_MCP_CONFIG` | — | `agent.tools.mcp.config` (default `./mcp.json`) |
| MCP call timeout | `AGENT_MCP_TIMEOUT` | — | `agent.tools.mcp.default-timeout-seconds` (default `60`) |
| MCP output limit | `AGENT_MCP_OUTPUT_LIMIT` | — | `agent.tools.mcp.output-limit` (default `8000`) |
| MCP tool refresh | `AGENT_MCP_REFRESH` | — | `agent.tools.mcp.refresh-seconds` (default `300`, `0` disables) |
| HTTP proxy | `AGENT_HTTP_PROXY` | — | env only; e.g. `127.0.0.1:7890` — all outbound HTTP (LLM, MCP, OAuth) goes through it |

Copy the example file and fill in real values:

```bash
cp .env.example .env
```

```bash
# .env
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=your-api-key-here
LLM_MODEL=gpt-4o-mini
AGENT_JWT_SECRET=replace-with-a-long-random-secret-value
```

`.env` is gitignored. Do not commit real keys.

## Run the CLI

```bash
./gradlew run
# or
./gradlew :cli:run
```

Options:

```bash
./gradlew :cli:run --args="--stream"       # stream replies (default)
./gradlew :cli:run --args="--no-stream"    # wait for the full reply
./gradlew :cli:run --args="--help"
```

### Console commands

| Command | Action |
|---|---|
| *(any text)* | Send a chat message |
| `/stream` or `/mode` | Toggle stream ↔ full replies |
| `/status` | Show base URL, model, mode, and memory size |
| `/history` or `/memory` | Show remembered conversation turns |
| `/clear` or `/reset` | Clear conversation memory |
| `/help` | Show help |
| `/quit` / `/exit` | Leave |

The console keeps multi-turn memory in session: each message is sent with prior user/assistant turns so the model has context. `/clear` starts a fresh conversation.

### Memory compression

By default the full history is sent with every request, so long conversations grow without bound. Enable compression (on by default) to keep the payload bounded:

- Compression only shrinks the context sent to the model — the stored history keeps every turn, so `/history` and `GET /api/v1/sessions/{id}/messages` still show the whole conversation.
- When the context passes `max-messages` (default 20), the oldest turns are folded into a single summary message.
- The most recent `keep-messages` turns (default 8) always stay verbatim; the cut never splits a turn and never orphans a tool result.
- The summary is regenerated incrementally — the previous summary plus the turns that just aged out — so the cost does not grow with the conversation.
- The summary body is capped at `max-summary-chars` (default 4000): the summarisation call passes the same number as `max_tokens`, and any overlong output is truncated, so the payload stays bounded even over very long sessions.
- Request layout: `[system prompt][summary][recent turns]`. The summary carries the `【历史对话摘要】` marker and is hidden from `/history` and from `GET /api/v1/sessions/{id}/messages`.
- Compression runs **after** the turn, on a background thread, so the user never waits for the summariser: the turn that crosses the threshold pays one uncompressed payload, and the next turn already sees the folded context. The fold is prepared and applied under the session lock; the summarisation call runs outside it. If the summarisation call fails the history is left untouched and the next turn retries.

The summarisation prompt lives in its own file so it can be tuned without touching code:
`llm-core/src/main/resources/dev/kualu/llm/summarize-instruction.txt`. Override it per deployment with `AGENT_MEMORY_INSTRUCTION` / `agent.memory.instruction` (a blank value falls back to the file).

Logs (slf4j; `web` uses logback, `cli` prints to stderr via `slf4j-simple`):

| Event | Level |
|---|---|
| Compression succeeded (`context 24 -> 6 (folded 14 turns, …)`) | `INFO` |
| Summarisation failed, history unchanged | `WARN` |
| Skipped (no legal cut point) | `DEBUG` |
| Thresholds loaded at startup | `INFO` |

Thresholds are validated at startup: `keep-messages >= 2` and `max-messages > keep-messages + 2`, otherwise the process refuses to start.

### Turn timing (web)

Every request through `SessionController` emits one `turn-timing` INFO line with the per-segment cost of that turn:

```
turn-timing endpoint=send session=<id> total=2280ms load=120ms(sql=15,decode=105,chars=2150400,count=340) list=- compress=skipped(0ms,summaryChars=0) llm=1800ms(ttft=450ms,rounds=2) tools=61000ms(count=2:exec=45000,mcp_fetch=16000) persist=340ms(encode=120,write=220,chars=2252800)
```

Segments that did not run print as `-`. Payload sizes are in characters (JSON length), not bytes. `tools` is the wall time of tool executions between model rounds (exec/MCP), aggregated per tool name — the gap between `total` and the segment sum is usually tools plus per-tool overhead. The same segments are recorded into Micrometer (`agent.turn.total|load|list|compress|llm|llm.ttft|tools|persist` timers, `agent.turn.tool` per tool name, `agent.turn.payload.read|write`, `agent.turn.messages`, `agent.turn.llm.rounds`, `agent.turn.tools.count` summaries) — tagged by `endpoint` (and `tool`) only, never by session. Metrics are served at `/actuator/metrics` and health at `/actuator/health`; `/actuator/**` (except health) requires the `ADMIN` role.

Only the exact command names above are intercepted. Everything else is chat input — including absolute paths (`/usr/lib/jvm/jdk-21.0.5+11/bin/java`), fractions (`1/2`), and other text that contains `/`.

## Exec tool

The model can run system commands through the `exec` tool (OpenAI-style function calling). Both the CLI and the web gateway expose it; tool rounds run inside the turn and the tool results feed back to the model automatically.

- **Windows** runs commands through `cmd.exe /c` (the console is switched to UTF-8 code page 65001 for the command); **Linux** and **macOS** use `/bin/sh -c`.
- The result carries the exit code and the combined stdout/stderr. A command that overruns the timeout (default 60s) is killed — including its child processes — and reported as a timeout.
- Output is truncated to the configured limit (default 8000 characters) so one command cannot flood the conversation.
- The optional `workdir` argument runs the command in another directory (default: the process working directory).
- At most 8 model calls run per user turn. Unknown tool names and failing tools are returned to the model as error text instead of aborting the turn.
- Disable with `AGENT_EXEC_ENABLED=false` (CLI) or `agent.tools.exec.enabled=false` (web): requests then carry no tools at all.

> **Security note:** the exec tool lets the model run arbitrary commands on the host. Keep the service on localhost and disable the tool when the gateway is reachable by users who should not have shell access.

## MCP tools

The model can also call tools from [MCP](https://modelcontextprotocol.io) servers — local stdio processes (like `docker run -i --rm ghcr.io/github/github-mcp-server`) and remote Streamable HTTP endpoints (like the hosted GitHub MCP server at `https://api.githubcopilot.com/mcp/`).

The primary configuration is the ecosystem-standard **`mcp.json`** (Claude Desktop / VS Code shape), read from `./mcp.json` by default (`AGENT_MCP_CONFIG` changes the path):

```json
{
  "mcpServers": {
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp/",
      "headers": { "Authorization": "Bearer ${GITHUB_PAT}" }
    },
    "playwright": {
      "command": "npx",
      "args": ["@playwright/mcp@latest"],
      "env": { "PLAYWRIGHT_BROWSER": "chromium" }
    }
  }
}
```

- The top-level key may be `mcpServers` or `servers`; entries with `command` are stdio, entries with `type: "http"` (or `url`) are remote. The legacy `type: "sse"` transport is rejected at load time.
- Strings support `${ENV}` placeholders (read from the process environment or the project `.env`; and VS Code-style `${input:id}`, read from `INPUT_ID`); an unresolved placeholder fails startup instead of leaking the template to the wire.
- The web gateway additionally accepts inline `agent.tools.mcp.servers` entries in `application.yml` as a deployment-level override layer (same-named entries win over `mcp.json`).

**Authentication for remote servers** (both are supported):

- **Static headers** — any `headers` map (e.g. a GitHub PAT as `Authorization: Bearer …`). This is the `mcp.json` shape used by VS Code for token-based access.
- **OAuth** — servers without headers (or `auth: "oauth"`) log in interactively: the system browser opens for an authorization-code + PKCE flow with a loopback redirect (`http://127.0.0.1:<port>/callback`), with the device-code grant as fallback on headless hosts. Refresh tokens are persisted (`<user.home>/.personal-assistant/mcp-tokens.json` by default, `agent.mcp_tokens` when `agent.store=postgres`), so a restart does not need a fresh login. A pre-registered client id can be supplied by the operator; otherwise dynamic client registration (RFC 7591) is used.

Tools are discovered at startup and refreshed on `notifications/tools/list_changed` and periodically; a dropped connection is retried with capped exponential backoff. Each tool is exposed to the model as `mcp_<server>_<tool>` (the original name is used when calling the server). Tool results are truncated to the output limit and failures come back to the model as `error: …` text instead of aborting the turn.

**Per-user servers** — web users can also add their own MCP servers under 头像菜单 → 设置 → MCP 服务器 (`/api/v1/settings/mcp-servers`), stored per account in `agent.user_mcp_servers` (or in memory with `agent.store=memory`). Credentials are personal and never leave the owner's tool set; the API only ever returns them masked (`••••••••` — resubmit the mask to keep a stored value, clear a field to drop the key). User-defined HTTP servers authenticate with static headers only (no OAuth — that flow opens a browser on the host and stays an operator concern); stdio servers run as local child processes, same trust level as below. A user server with the same name as a `mcp.json` entry overrides it for that user's turns only.

> **Security note:** MCP servers are third-party code. A stdio server runs as a **local child process** (same trust level as the exec tool) and a remote server receives **your credentials** — configure only servers you trust. Remote URLs are forced to HTTPS (loopback excepted) so tokens never cross the network in plaintext. Tool results are untrusted input to the model (prompt-injection surface). Keep `mcp.json` files with secrets out of version control.

## Run the web gateway

```bash
export AGENT_JWT_SECRET='replace-with-a-long-random-secret-value'
./gradlew :web:bootRun
# or
./gradlew :web:bootJar
java -jar web/build/libs/agent-web-1.0-SNAPSHOT.jar
```

Default bind: `http://127.0.0.1:8080` (`SERVER_PORT` / `server.port`).
Health and login are public; all other `/api/v1/**` routes require a Bearer JWT.

> **Security note:** put TLS and network controls in front of this service before exposing it beyond localhost. Change the default admin password. The JWT secret must come from the environment or a secret store.

### Auth

```bash
curl -s http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"change-me"}'
```

Response:

```json
{ "token": "<jwt>", "tokenType": "Bearer", "expiresIn": 7200, "refreshToken": "<opaque>" }
```

Sessions are server-side (one row per login in `agent.refresh_tokens`). The access JWT is bound to its session via a `sid` claim and expires after `AGENT_JWT_TTL`; the session itself only expires after `AGENT_REFRESH_IDLE_TIMEOUT` (default `24h`) without any authenticated request — refreshing the pair counts as activity, `logout` revokes immediately.

Users and roles are configured under `agent.security.users` (see `web/src/main/resources/application.yml`).
Roles: `USER` (sessions/chat), `ADMIN` (adds `GET /api/v1/admin/users`).

Change your own password (requires a Bearer token):

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/auth/change-password \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"old-password","newPassword":"at-least-8-chars"}'
```

New passwords need at least 8 characters; a wrong `currentPassword` is `400`. The current session keeps working until it goes idle (`agent.security.refresh-idle-timeout`) or is revoked with `POST /api/v1/auth/logout` — only future logins use the new password. With `agent.store=memory` the change lasts until restart; with `agent.store=postgres` it is written to `agent.users` and survives restarts.

### API

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/health` | public | `{status, llmConfigured}` |
| POST | `/api/v1/auth/login` | public | Issue access + refresh token pair |
| POST | `/api/v1/auth/refresh` | public | `{refreshToken}` → rotated pair (401 when idle/unknown) |
| POST | `/api/v1/auth/logout` | public | `{refreshToken}` → `204`, revokes the session |
| POST | `/api/v1/auth/change-password` | USER | `{currentPassword, newPassword}` → `204` |
| GET | `/api/v1/sessions` | USER | List your sessions |
| POST | `/api/v1/sessions` | USER | Create session `{systemPrompt?}` |
| GET | `/api/v1/sessions/{id}` | USER | Session metadata |
| DELETE | `/api/v1/sessions/{id}` | USER | Delete session |
| GET | `/api/v1/sessions/{id}/messages` | USER | Message history |
| GET | `/api/v1/sessions/{id}/images/{imageId}` | USER | Stored message image (raw bytes) |
| POST | `/api/v1/sessions/{id}/messages` | USER | `{content, images?}` → `{reply}` |
| POST | `/api/v1/sessions/{id}/messages/stream` | USER | `{content, images?}` → SSE stream |
| GET | `/api/v1/admin/users` | ADMIN | List configured users |

SSE events: `delta` (`{"text"}`), then `done` (`{"reply"}`) or `error` (`{"message"}`).

Error body: `{ "status", "error", "message" }`.
Chat is `503` when LLM is not configured, `502` when the upstream LLM fails.

**Image input (vision).** A message may carry `images`: up to 10 entries, each a
`data:image/...` base64 URI or an `http(s)` image URL (≤ 25M characters each).
Text and images travel together as OpenAI-style multimodal `content` parts, so
`LLM_MODEL` must be a vision-capable model. `content` may be blank when images
are present (recognition without a question); at least one of the two is
required. History entries come back as `{role, content, images?}` — the frontend
renders the images in the conversation.

Sessions are isolated per authenticated user (404 if not found or not yours).
Sessions and accounts are persisted to PostgreSQL by default and survive restarts; see [Persistence](#persistence).

### Persistence

By default (`agent.store=postgres`) users and conversations live in PostgreSQL:

| Table | Holds |
|---|---|
| `agent.users` | `username`, bcrypt `password_hash`, `roles text[]` |
| `agent.sessions` | `id`, `owner` (FK to users), `created_at`, `messages jsonb` |
| `agent.session_images` | `session_id`, `image_id` (SHA-256 of content), `content` — externalised `data:` images |

- **Schema** is managed by Flyway (`web/src/main/resources/db/migration`), applied at startup in the `agent` schema. Never edit a shipped migration — add `V2__*.sql` instead.
- **Users** are seeded from `agent.security.users` on first start only (`insert … on conflict do nothing`). After that the database is the source of truth: a password or role changed by hand is never overwritten, and removing a user from config does not delete the account. Changing your own password is `POST /api/v1/auth/change-password` (see [Auth](#auth)); adding a user or resetting someone else's password is SQL for now — there is no admin API for that yet.
- **The whole conversation** (including any [compression summary](#memory-compression)) is stored as one ordered JSON array per session, so a restart resumes exactly where it left off.
- **`data:` images are externalised** to `agent.session_images` (content-addressed) and the jsonb carries `ref:<sessionId>/<imageId>` markers instead of pixels, so per-turn writes stay small. History returns the refs as `GET /api/v1/sessions/{id}/images/{imageId}` URLs (fetched with auth), and the LLM request materialises them back into data URIs for vision models. Listings never read the payload at all (`jsonb_array_length` gives the count). `V5__externalize_session_images.sql` backfills rows written before this split (idempotent — safe to re-run).
- **Credentials never live in source.** Put `AGENT_DB_PASSWORD` in `.env` (gitignored) or the environment. The URL must state its TLS intent (`sslmode=require`) because the password would otherwise cross the network in plaintext.
- **`agent.store=memory`** reverts to process-local storage: no database is contacted at all, and the app warns loudly at startup. Intended for local experiments and tests only.

The health endpoint reports database reachability without needing authentication:

```bash
curl -s http://127.0.0.1:8080/api/v1/health
# { "status": "UP", "llmConfigured": true, "dbUp": true }
```

Example:

```bash
TOKEN=$(curl -s http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"change-me"}' | jq -r .token)

SID=$(curl -s http://127.0.0.1:8080/api/v1/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"systemPrompt":"Be brief."}' | jq -r .id)

curl -s "http://127.0.0.1:8080/api/v1/sessions/$SID/messages" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"Hello"}'

curl -N "http://127.0.0.1:8080/api/v1/sessions/$SID/messages/stream" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"Tell me a short story"}'
```

## Use as a library

### LLM client

```java
import dev.kualu.llm.client.LlmClient;

LlmClient client = LlmClient.fromEnvironment();

// Full reply
String reply = client.chat("Hello");

// Streaming — print deltas as they arrive
client.chatStream("Tell me a story", delta -> System.out.print(delta));

// Streaming — Stream<String> of deltas (close when done)
try (var deltas = client.chatStream("Tell me a story")) {
    deltas.forEach(System.out::print);
}
```

Multi-turn memory — send the full conversation so the model sees prior context:

```java
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.client.LlmClient;

LlmClient client = LlmClient.fromEnvironment();
ConversationMemory memory = new ConversationMemory()
        .addSystem("You are a terse assistant.");

String user = "My name is Ada.";
String reply = client.chat(memory.withUser(user));
memory.addUser(user).addAssistant(reply);

// Next turn includes the earlier exchange
String followUp = "What is my name?";
String reply2 = client.chat(memory.withUser(followUp));
memory.addUser(followUp).addAssistant(reply2);
```

Or pass messages directly:

```java
String reply = client.chat(List.of(
        ChatMessage.system("be brief"),
        ChatMessage.user("Hi"),
        ChatMessage.assistant("Hello"),
        ChatMessage.user("Bye")));
```

User turns may carry images (URLs or `data:` URIs) for vision models — the
client serializes them as multimodal `image_url` content parts:

```java
String reply = client.chat(List.of(
        ChatMessage.user("What is in this picture?",
                List.of("data:image/png;base64,iVBORw0KGgo..."))));
```

Tool results use `role: "tool"` and carry the matching `tool_call_id`:

```java
memory.addUser("run ls");
memory.addTool("file1\nfile2", "call_abc");
// or: ChatMessage.tool("file1\nfile2", "call_abc")
```

`ConversationMemory.withUser(...)` builds the request payload without mutating history; call `addUser` / `addAssistant` only after a successful reply so failed turns are not remembered.

To keep the payload bounded over a long session, wrap the memory with `MemoryCompressor` (see [Memory compression](#memory-compression)):

```java
import dev.kualu.llm.memory.MemoryCompressor;

MemoryCompressor compressor = new MemoryCompressor(client, 20, 8, null);
// before each request:
compressor.maybeCompress(memory, "session=abc");
List<ChatMessage> payload = memory.withUser(user);
```

Explicit config:

```java
var config = new LlmConfig("https://api.openai.com/v1", "sk-...", "gpt-4o-mini");
LlmClient client = new LlmClient(config);
```

Errors are thrown as `LlmException` (status code + response body available via `statusCode()` / `responseBody()`).

### HTTP client

```java
import dev.kualu.http.HttpClient;

HttpClient http = new HttpClient();
var response = http.post(
    "https://httpbin.org/post",
    Map.of("Content-Type", "application/json"),
    "{\"ok\":true}");
System.out.println(response.statusCode() + " " + response.body());
```

Streaming responses:

```java
var response = http.postLines(url, headers, body);
try (var lines = response.body()) {
    lines.forEach(System.out::println);
}
```

## JPMS

Module name: **`dev.kualu.agent`** (from `llm-core`)

```java
module your.app {
    requires dev.kualu.agent;
}
```

## Tests

```bash
./gradlew test
```

Covers HTTP client, LLM config/`chat`/`chatStream` (including multi-turn message lists, multimodal image content, SSE, and error paths), the function-calling protocol (`tools`, `tool_calls` parsing and streaming), the tool-call loop and the cross-platform exec tool (exit codes, timeout kill, output truncation, working directory), conversation memory, memory compression (layout, incremental summary, cut-point safety, failure handling), console commands, session store isolation, JWT login/security, and REST session/message APIs.

Persistence is covered by integration tests that run a real PostgreSQL via
[io.zonky.test:embedded-postgres](https://github.com/zonkyio/embedded-postgres) — no Docker required.
They apply the real Flyway migrations and verify owner isolation, restart-equivalent reads,
compressed-summary round-trips, and that a seeded account is never overwritten.
