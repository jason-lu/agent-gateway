package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.tools.ToolLoop;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end: model requests an MCP tool, the loop feeds the remote result back. */
class ToolLoopMcpIntegrationTest {

    @Test
    void modelCallsMcpToolAndReceivesTheRemoteResult() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> {
            if (!message.has("id")) {
                return null;
            }
            String method = message.get("method").getAsString();
            JsonObject result = new JsonObject();
            switch (method) {
                case "initialize" -> {
                    result.addProperty("protocolVersion", "2025-06-18");
                    result.add("capabilities", new JsonObject());
                    JsonObject info = new JsonObject();
                    info.addProperty("name", "e2e-server");
                    result.add("serverInfo", info);
                }
                case "tools/list" -> {
                    JsonArray tools = new JsonArray();
                    JsonObject tool = new JsonObject();
                    tool.addProperty("name", "echo");
                    tool.addProperty("description", "Echoes text");
                    tool.add("inputSchema", new JsonObject());
                    tools.add(tool);
                    result.add("tools", tools);
                }
                case "tools/call" -> {
                    String text = message.getAsJsonObject("params")
                            .getAsJsonObject("arguments").get("text").getAsString();
                    JsonArray content = new JsonArray();
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "text");
                    block.addProperty("text", "remote says: " + text);
                    content.add(block);
                    result.add("content", content);
                }
                default -> {
                    // empty result
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", message.get("id").getAsLong());
            response.add("result", result);
            return response;
        };

        try (McpConnectionManager manager = new McpConnectionManager(
                List.of(McpServerConfig.stdio("demo", List.of("cmd"), null)),
                Duration.ofSeconds(2), Duration.ofSeconds(2), 1000, Duration.ZERO,
                config -> transport, new dev.kualu.mcp.auth.InMemoryTokenStore())) {
            manager.start();
            assertEquals(List.of("mcp_demo_echo"), manager.tools().stream().map(t -> t.name()).toList());

            ScriptedLlm llm = new ScriptedLlm();
            llm.replyWith("", new ToolCall("call-1", "mcp_demo_echo", "{\"text\":\"hi\"}"));
            llm.replyWith("done", new ToolCall[0]);

            ToolLoop loop = new ToolLoop(llm, manager::tools);
            ConversationMemory memory = new ConversationMemory();
            String reply = loop.run(memory, "please echo hi");

            assertEquals("done", reply);
            assertEquals(2, llm.requests.size());
            // second payload carries the assistant tool-call turn and the remote result
            List<ChatMessage> second = llm.requests.get(1);
            assertTrue(second.stream().anyMatch(m ->
                    "tool".equals(m.role()) && m.content().contains("remote says: hi")),
                    "remote tool result must be fed back to the model");
        }
    }

    /** Scripted model: each request consumes the next canned turn. */
    private static final class ScriptedLlm extends LlmClient {

        final Deque<AssistantTurn> script = new ArrayDeque<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();

        ScriptedLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        void replyWith(String content, ToolCall... calls) {
            script.add(new AssistantTurn(content, List.of(calls)));
        }

        @Override
        public AssistantTurn complete(List<ChatMessage> messages, List<ToolSpec> tools) {
            requests.add(List.copyOf(messages));
            return script.removeFirst();
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            requests.add(List.copyOf(messages));
            return script.removeFirst().content();
        }
    }
}
