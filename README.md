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
| Admin user | `AGENT_ADMIN_USERNAME` / `AGENT_ADMIN_PASSWORD` | — | `agent.security.users` |

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
Storage is a pluggable `SessionStore`; the default `InMemorySessionStore` keeps history in memory only.

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

Covers HTTP client, LLM config/`chat`/`chatStream` (including multi-turn message lists, SSE, and error paths), conversation memory, console commands, session store isolation, JWT login/security, and REST session/message APIs.
