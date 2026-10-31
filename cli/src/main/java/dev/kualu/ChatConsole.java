package dev.kualu;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ConversationMemory;
import dev.kualu.llm.LlmClient;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.MemoryCompressor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
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
 */
public class ChatConsole {

    private final LlmClient client;
    private final BufferedReader in;
    private final PrintStream out;
    private final ConversationMemory memory;
    private final MemoryCompressor compressor;
    private boolean streaming;

    /** Runs without memory compression. */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming) {
        this(client, in, out, streaming, null);
    }

    /** @param compressor folds old turns into a summary; {@code null} disables compression */
    public ChatConsole(LlmClient client, BufferedReader in, PrintStream out, boolean streaming,
                       MemoryCompressor compressor) {
        this.client = Objects.requireNonNull(client, "client");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.memory = new ConversationMemory();
        this.compressor = compressor;
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
            out.println("  " + (i + 1) + ". [" + message.role() + "] " + preview(message.content()));
        }
    }

    private static String preview(String content) {
        String oneLine = content.replace('\n', ' ');
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 77) + "...";
    }

    private void sendToLlm(String message) {
        if (compressor != null) {
            compressor.maybeCompress(memory, "cli");
        }
        List<ChatMessage> payload = memory.withUser(message);
        out.print("assistant: ");
        out.flush();
        try {
            String reply;
            if (streaming) {
                reply = client.chatStream(payload, delta -> {
                    out.print(delta);
                    out.flush();
                });
            } else {
                reply = client.chat(payload);
                out.print(reply);
            }
            out.println();
            memory.addUser(message);
            memory.addAssistant(reply);
        } catch (LlmException e) {
            out.println();
            out.println("error: " + e.getMessage());
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
