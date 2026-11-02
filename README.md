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

| Setting | Primary | Alias | Web property |
|---|---|---|---|
| Base URL | `LLM_BASE_URL` | `BASE_URL` | `llm.base-url` |
| API key | `LLM_API_KEY` | `API_KEY` | `llm.api-key` |
| Model | `LLM_MODEL` | `MODEL` | `llm.model` |
| JWT secret | `AGENT_JWT_SECRET` | — | `agent.security.jwt-secret` (min 32 chars, required to start web) |
| JWT TTL | `AGENT_JWT_TTL` | — | `agent.security.jwt-ttl` (default `2h`) |
| Admin user | `AGENT_ADMIN_USERNAME` / `AGENT_ADMIN_PASSWORD` | — | `agent.security.users` (first-start seed only) |
| Persistence backend | `AGENT_STORE` | — | `agent.store` (default `postgres`, or `memory`) |
| Database URL | `AGENT_DB_URL` | — | `agent.db.url` (must set `sslmode`) |
| Database user | `AGENT_DB_USERNAME` | — | `agent.db.username` (default `postgres`) |
| Database password | `AGENT_DB_PASSWORD` | — | `agent.db.password` (**no default, never in source**) |
| Memory compression | `AGENT_MEMORY_ENABLED` | — | `agent.memory.enabled` (default `true`) |
| Compression threshold | `AGENT_MEMORY_MAX_MESSAGES` | — | `agent.memory.max-messages` (default `20`) |
| Turns kept verbatim | `AGENT_MEMORY_KEEP_MESSAGES` | — | `agent.memory.keep-messages` (default `8`) |
| Summary instruction | `AGENT_MEMORY_INSTRUCTION` | — | `agent.memory.instruction` (default: `summarize-instruction.txt`) |

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

- When the history passes `max-messages` (default 20), the oldest turns are folded into a single summary message.
- The most recent `keep-messages` turns (default 8) always stay verbatim; the cut never splits a turn and never orphans a tool result.
- The summary is regenerated incrementally — the previous summary plus the turns that just aged out — so the cost does not grow with the conversation.
- Layout: `[system prompt][summary][recent turns]`. The summary carries the `【历史对话摘要】` marker and is hidden from `/history` and from `GET /api/v1/sessions/{id}/messages`.
- Compression runs before the request is built, so the current turn already sees the bounded payload. If the summarisation call fails the history is left untouched and the turn proceeds normally.

The summarisation prompt lives in its own file so it can be tuned without touching code:
`llm-core/src/main/resources/dev/kualu/llm/summarize-instruction.txt`. Override it per deployment with `AGENT_MEMORY_INSTRUCTION` / `agent.memory.instruction` (a blank value falls back to the file).

Logs (slf4j; `web` uses logback, `cli` prints to stderr via `slf4j-simple`):

| Event | Level |
|---|---|
| Compression succeeded (`size 24 -> 12 (dropped 14, …)`) | `INFO` |
| Summarisation failed, history unchanged | `WARN` |
| Skipped (no legal cut point) | `DEBUG` |
| Thresholds loaded at startup | `INFO` |

Thresholds are validated at startup: `keep-messages >= 2` and `max-messages > keep-messages + 2`, otherwise the process refuses to start.

Only the exact command names above are intercepted. Everything else is chat input — including absolute paths (`/usr/lib/jvm/jdk-21.0.5+11/bin/java`), fractions (`1/2`), and other text that contains `/`.

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
{ "token": "<jwt>", "tokenType": "Bearer", "expiresIn": 7200 }
```

Users and roles are configured under `agent.security.users` (see `web/src/main/resources/application.yml`).
Roles: `USER` (sessions/chat), `ADMIN` (adds `GET /api/v1/admin/users`).

### API

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/health` | public | `{status, llmConfigured}` |
| POST | `/api/v1/auth/login` | public | Issue JWT |
| GET | `/api/v1/sessions` | USER | List your sessions |
| POST | `/api/v1/sessions` | USER | Create session `{systemPrompt?}` |
| GET | `/api/v1/sessions/{id}` | USER | Session metadata |
| DELETE | `/api/v1/sessions/{id}` | USER | Delete session |
| GET | `/api/v1/sessions/{id}/messages` | USER | Message history |
| POST | `/api/v1/sessions/{id}/messages` | USER | `{content}` → `{reply}` |
| POST | `/api/v1/sessions/{id}/messages/stream` | USER | `{content}` → SSE stream |
| GET | `/api/v1/admin/users` | ADMIN | List configured users |

SSE events: `delta` (`{"text"}`), then `done` (`{"reply"}`) or `error` (`{"message"}`).

Error body: `{ "status", "error", "message" }`.
Chat is `503` when LLM is not configured, `502` when the upstream LLM fails.

Sessions are isolated per authenticated user (404 if not found or not yours).
Sessions and accounts are persisted to PostgreSQL by default and survive restarts; see [Persistence](#persistence).

### Persistence

By default (`agent.store=postgres`) users and conversations live in PostgreSQL:

| Table | Holds |
|---|---|
| `agent.users` | `username`, bcrypt `password_hash`, `roles text[]` |
| `agent.sessions` | `id`, `owner` (FK to users), `created_at`, `messages jsonb` |

- **Schema** is managed by Flyway (`web/src/main/resources/db/migration`), applied at startup in the `agent` schema. Never edit a shipped migration — add `V2__*.sql` instead.
- **Users** are seeded from `agent.security.users` on first start only (`insert … on conflict do nothing`). After that the database is the source of truth: a password or role changed by hand is never overwritten, and removing a user from config does not delete the account. Changing a password or adding a user is SQL for now — there is no admin API yet.
- **The whole conversation** (including any [compression summary](#memory-compression)) is stored as one ordered JSON array per session, so a restart resumes exactly where it left off.
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
import dev.kualu.llm.LlmClient;

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
import dev.kualu.llm.ConversationMemory;
import dev.kualu.llm.LlmClient;

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

Tool results use `role: "tool"` and carry the matching `tool_call_id`:

```java
memory.addUser("run ls");
memory.addTool("file1\nfile2", "call_abc");
// or: ChatMessage.tool("file1\nfile2", "call_abc")
```

`ConversationMemory.withUser(...)` builds the request payload without mutating history; call `addUser` / `addAssistant` only after a successful reply so failed turns are not remembered.

To keep the payload bounded over a long session, wrap the memory with `MemoryCompressor` (see [Memory compression](#memory-compression)):

```java
import dev.kualu.llm.MemoryCompressor;

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

Covers HTTP client, LLM config/`chat`/`chatStream` (including multi-turn message lists, SSE, and error paths), conversation memory, memory compression (layout, incremental summary, cut-point safety, failure handling), console commands, session store isolation, JWT login/security, and REST session/message APIs.

Persistence is covered by integration tests that run a real PostgreSQL via
[io.zonky.test:embedded-postgres](https://github.com/zonkyio/embedded-postgres) — no Docker required.
They apply the real Flyway migrations and verify owner isolation, restart-equivalent reads,
compressed-summary round-trips, and that a seeded account is never overwritten.
