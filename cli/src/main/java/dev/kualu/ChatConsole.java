package dev.kualu;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.tools.ToolLoop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Interactive console over {@link LlmClient}.
 * Type a message to chat. Use {@code /help} for commands, including the
 * {@code /stream} toggle between streaming and non-streaming replies.
 *
 * <p>Keeps a {@link ConversationMemory} so each turn sees prior context.
 * Use {@code /clear} to reset memory and {@code /history} to inspect it.
 * Tool calls (the exec tool) are run by {@link ToolLoop} inside the turn.
 */
public class ChatConsole {

    private final LlmClient client;
    private final BufferedReader in;
    private final PrintStream out;
    private final ConversationMemory memory;
    private final MemoryCompressor compressor;
    private final ToolLoop toolLoop;
    private final dev.kualu.llm.moderation.ContentModerator moderator;
    private boolean streaming;

    /** Runs without memory compression or tools. */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming) {
        this(client, in, out, streaming, null, List.of());
    }

    /** @param compressor folds old turns into a summary; {@code null} disables compression */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming,
                       MemoryCompressor compressor) {
        this(client, in, out, streaming, compressor, List.of());
    }

    /** @param tools tools the model may call (e.g. {@code ExecTool}); empty disables tool calling */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming,
                       MemoryCompressor compressor, List<AgentTool> tools) {
        this(client, in, out, streaming, compressor, () -> tools);
    }

    /** @param tools supplies the tool list once per turn (e.g. dynamic MCP tools) */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming,
                       MemoryCompressor compressor, java.util.function.Supplier<List<AgentTool>> tools) {
        this(client, in, out, streaming, compressor, tools, null);
    }

    /** @param moderator content-moderation pipeline; {@code null} disables moderation */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming,
                       MemoryCompressor compressor, java.util.function.Supplier<List<AgentTool>> tools,
                       dev.kualu.llm.moderation.ContentModerator moderator) {
        this.client = Objects.requireNonNull(client, "client");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.memory = new ConversationMemory();
        this.compressor = compressor;
        this.toolLoop = new ToolLoop(client, tools);
        this.moderator = moderator;
        this.streaming = streaming;
    }

    /** Runs until EOF or {@code /quit}. */
    public void run() {
        printBanner();
        while (true) {
            out.print(prompt());
            out.flush();

            String line = readLine();
            if (line == null) {
                out.println();
                out.println("Bye.");
                return;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("/") && isCommand(line)) {
                if (handleCommand(line)) {
                    return;
                }
                continue;
            }
            sendToLlm(line);
        }
    }

    private void printBanner() {
        out.println("LLM Console");
        out.println("  base URL : " + client.config().baseUrl());
        out.println("  model    : " + client.config().model());
        out.println("  mode     : " + modeLabel());
        out.println("  memory   : on (use /clear to reset, /history to inspect)");
        out.println("Type a message to chat, or /help for commands.");
        out.println();
    }

    private String prompt() {
        return "you (" + (streaming ? "stream" : "full") + ")> ";
    }

    private String modeLabel() {
        return streaming ? "stream" : "full";
    }

    /** True when the line is a console command. Anything else (including content with {@code /}) is chat. */
    private static boolean isCommand(String line) {
        return isKnownCommand(commandToken(line));
    }

    private static String commandToken(String line) {
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        return line.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isKnownCommand(String command) {
        return switch (command) {
            case "/help", "/h", "/?",
            "/stream", "/mode",
            "/status",
            "/clear", "/reset",
            "/history", "/memory",
            "/quit", "/exit", "/q" -> true;
            default -> false;
        };
    }

    /** Returns {@code true} when the app should exit. */
    private boolean handleCommand(String line) {
        String command = commandToken(line);

        switch (command) {
            case "/help", "/h", "/?" -> printHelp();
            case "/stream", "/mode" -> {
                streaming = !streaming;
                out.println("Mode is now: " + modeLabel());
            }
            case "/status" -> printStatus();
            case "/clear", "/reset" -> {
                memory.clear();
                out.println("Memory cleared.");
            }
            case "/history", "/memory" -> printHistory();
            case "/quit", "/exit", "/q" -> {
                out.println("Bye.");
                return true;
            }
            default -> {
                // Unreachable while isCommand only accepts known commands; keep as a safe fallback.
                out.println("Unknown command: " + command + " (try /help)");
            }
        }
        return false;
    }

    private void printHelp() {
        out.println("Commands:");
        out.println("  /help             Show this help");
        out.println("  /stream | /mode   Toggle stream vs full replies (now: " + modeLabel() + ")");
        out.println("  /status           Show connection and mode info");
        out.println("  /history | /memory  Show remembered conversation turns");
        out.println("  /clear | /reset   Clear conversation memory");
        out.println("  /quit | /exit     Leave the console");
        out.println("  <text>            Send a chat message");
    }

    private void printStatus() {
        out.println("  base URL : " + client.config().baseUrl());
        out.println("  model    : " + client.config().model());
        out.println("  mode     : " + modeLabel());
        out.println("  memory   : " + memory.size() + " turn(s)");
    }

    private void printHistory() {
        List<ChatMessage> messages = memory.messages().stream()
                .filter(message -> !"system".equals(message.role())
                        || !message.content().startsWith(MemoryCompressor.SUMMARY_PREFIX))
                .toList();
        if (messages.isEmpty()) {
            out.println("Memory is empty.");
            return;
        }
        out.println("History (" + messages.size() + " turn(s)):");
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            out.println("  " + (i + 1) + ". [" + message.role() + "] " + preview(message));
        }
    }

    private static String preview(ChatMessage message) {
        if (message.content().isBlank() && message.toolCalls() != null) {
            return "tool calls: " + String.join(", ",
                    message.toolCalls().stream().map(ToolCall::name).toList());
        }
        String oneLine = message.content().replace('\n', ' ');
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 77) + "...";
    }

    private void sendToLlm(String message) {
        String effective = message;
        if (moderator != null) {
            var hit = moderator.checkInput(message);
            if (hit.isPresent()) {
                printFinding(hit.get(), dev.kualu.llm.moderation.Direction.INPUT);
                switch (hit.get().action()) {
                    case BLOCK -> {
                        return;
                    }
                    case REDACT -> effective = moderator.redact(message, hit.get());
                    case FLAG -> {
                    }
                }
            }
        }
        if (compressor != null) {
            compressor.maybeCompress(memory, "cli");
        }
        out.print("assistant: ");
        out.flush();
        try {
            String reply;
            if (streaming) {
                if (moderator != null) {
                    var gate = new dev.kualu.llm.moderation.SegmentGate(moderator, this::printDelta);
                    reply = toolLoop.run(memory, effective, gate);
                    gate.finish();
                } else {
                    reply = toolLoop.run(memory, effective, this::printDelta);
                }
                out.println();
                // The segments are already on screen; an L2-only finding is fixed
                // up here so history matches what the user is told.
                String finalReply = gateOutput(reply);
                if (!finalReply.equals(reply)) {
                    out.println(finalReply);
                }
            } else {
                reply = toolLoop.run(memory, effective);
                out.print(gateOutput(reply));
                out.println();
            }
        } catch (dev.kualu.llm.moderation.ModerationBlockedException ex) {
            printFinding(ex.hit(), dev.kualu.llm.moderation.Direction.OUTPUT);
            rewriteLastAssistant(dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL);
            out.println(dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL);
        } catch (LlmException e) {
            out.println();
            out.println("error: " + e.getMessage());
        }
    }

    private void printDelta(String delta) {
        out.print(delta);
        out.flush();
    }

    /** Output gate after a completed reply: rewrites history to the effective text. */
    private String gateOutput(String reply) {
        if (moderator == null) {
            return reply;
        }
        var hit = moderator.checkOutput(reply);
        if (hit.isEmpty()) {
            return reply;
        }
        printFinding(hit.get(), dev.kualu.llm.moderation.Direction.OUTPUT);
        return switch (hit.get().action()) {
            case BLOCK -> {
                rewriteLastAssistant(dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL);
                yield dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL;
            }
            case REDACT -> {
                String masked = moderator.redact(reply, hit.get());
                rewriteLastAssistant(masked);
                yield masked;
            }
            case FLAG -> reply;
        };
    }

    private void printFinding(dev.kualu.llm.moderation.ModerationHit hit,
                              dev.kualu.llm.moderation.Direction direction) {
        out.println();
        out.println("⚠ 内容未通过安全检查（类目：" + hit.category() + "，方向：" + direction + "）");
    }

    private void rewriteLastAssistant(String newContent) {
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("assistant".equals(message.role())) {
                messages.set(i, new ChatMessage("assistant", newContent, message.toolCallId(),
                        message.toolCalls(), null, message.usage()));
                memory.replaceAll(messages);
                return;
            }
        }
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
