# agent

A small modular Java HTTP + LLM client library with an interactive console chat app.

- **HTTP client** — send requests with URL, headers, and body
- **LLM client** — OpenAI-style `chat/completions` with full or streaming (SSE) replies
- **Console app** — chat in the terminal with multi-turn memory; toggle stream / non-stream mode

## Requirements

- Java 25+
- Gradle (wrapper included)

## Configuration

Credentials are read from **environment variables first**, then from a `.env` file in the project root.

| Setting | Primary | Alias |
|---|---|---|
| Base URL | `LLM_BASE_URL` | `BASE_URL` |
| API key | `LLM_API_KEY` | `API_KEY` |
| Model | `LLM_MODEL` | `MODEL` |

Copy the example file and fill in real values:

```bash
cp .env.example .env
```

```bash
# .env
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=your-api-key-here
LLM_MODEL=gpt-4o-mini
```

`.env` is gitignored. Do not commit real keys.

## Run the console app

```bash
./gradlew run
```

Options:

```bash
./gradlew run --args="--stream"       # stream replies (default)
./gradlew run --args="--no-stream"    # wait for the full reply
./gradlew run --args="--help"
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

Example session:

```
LLM Console
  base URL : https://api.openai.com/v1
  model    : gpt-4o-mini
  mode     : stream
  memory   : on (use /clear to reset, /history to inspect)
Type a message to chat, or /help for commands.

you (stream)> My name is Ada.
assistant: Nice to meet you, Ada.

you (stream)> What is my name?
assistant: Your name is Ada.

you (stream)> /history
History (4 turn(s)):
  1. [user] My name is Ada.
  2. [assistant] Nice to meet you, Ada.
  3. [user] What is my name?
  4. [assistant] Your name is Ada.

you (stream)> /clear
Memory cleared.
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

## Project layout

```
src/main/java/
  module-info.java          # JPMS module dev.kualu.agent
  dev/kualu/
    Main.java               # entry point → ChatConsole
    ChatConsole.java        # interactive REPL with multi-turn memory
    http/                   # transport
      HttpClient.java
      HttpClientException.java
    llm/                    # LLM protocol
      LlmClient.java
      LlmConfig.java
      LlmException.java
      ChatMessage.java      # one system/user/assistant turn
      ConversationMemory.java # multi-turn history for chat
      ChatResponses.java    # full JSON reply decoding
      SseContentDeltas.java # SSE delta parsing
      LlmErrorMessages.java # error message formatting
```

Module name: **`dev.kualu.agent`**

```java
module your.app {
    requires dev.kualu.agent;
}
```

## Tests

```bash
./gradlew test
```

Covers the HTTP client, LLM config/`chat`/`chatStream` (including multi-turn message lists, SSE, and error paths), conversation memory, and console command handling.
