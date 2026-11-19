package dev.kualu.llm.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    /** What the stub answers with; overwritten per test. */
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private WebSearchTool tool() {
        return tool(8000);
    }

    private WebSearchTool tool(int outputLimit) {
        return new WebSearchTool(new WebSearchTool.Config(
                "test-key", baseUrl, 5, outputLimit, Duration.ofSeconds(10), true));
    }

    private static String resultJson() {
        return """
                {
                  "query": "who won the 2018 world cup",
                  "answer": "France won the 2018 FIFA World Cup.",
                  "results": [
                    {"title": "FIFA World Cup 2018", "url": "https://example.com/fifa", "content": "France beat Croatia 4-2.", "score": 0.97},
                    {"title": "World Cup Final", "url": "https://example.com/final", "content": "The final was played in Moscow.", "score": 0.91}
                  ],
                  "response_time": 1.2
                }
                """;
    }

    @Test
    void returnsAnswerAndResults() {
        responseBody = resultJson();

        String result = tool().execute("{\"query\":\"who won the 2018 world cup\"}");

        assertTrue(result.contains("answer: France won the 2018 FIFA World Cup."), result);
        assertTrue(result.contains("[1] FIFA World Cup 2018 (score 0.97)"), result);
        assertTrue(result.contains("https://example.com/fifa"), result);
        assertTrue(result.contains("France beat Croatia 4-2."), result);
        assertTrue(result.contains("[2] World Cup Final (score 0.91)"), result);
    }

    @Test
    void sendsBearerAuthAndQuery() {
        responseBody = resultJson();

        tool().execute("{\"query\":\"hello world\"}");

        assertEquals("Bearer test-key", lastAuthHeader.get());
        JsonObject sent = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject();
        assertEquals("hello world", sent.get("query").getAsString());
        assertEquals(5, sent.get("max_results").getAsInt());
        assertTrue(sent.get("include_answer").getAsBoolean());
    }

    @Test
    void optionalArgumentsAreForwardedToTheApi() {
        responseBody = resultJson();

        tool().execute("{\"query\":\"q\",\"max_results\":3,\"topic\":\"news\","
                + "\"search_depth\":\"advanced\",\"time_range\":\"week\"}");

        JsonObject sent = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject();
        assertEquals(3, sent.get("max_results").getAsInt());
        assertEquals("news", sent.get("topic").getAsString());
        assertEquals("advanced", sent.get("search_depth").getAsString());
        assertEquals("week", sent.get("time_range").getAsString());
    }

    @Test
    void missingQueryIsReportedAsErrorText() {
        String result = tool().execute("{}");

        assertTrue(result.startsWith("error: missing required argument 'query'"), result);
    }

    @Test
    void invalidArgumentsAreReportedAsErrorText() {
        assertTrue(tool().execute("not json").startsWith("error:"), "bad JSON");
        assertTrue(tool().execute("\"str\"").startsWith("error:"), "non-object");
        assertTrue(tool().execute("{\"query\":\"q\",\"max_results\":0}").startsWith("error:"), "max too small");
        assertTrue(tool().execute("{\"query\":\"q\",\"max_results\":99}").startsWith("error:"), "max too big");
        assertTrue(tool().execute("{\"query\":\"q\",\"topic\":\"sports\"}").startsWith("error:"), "bad topic");
        assertTrue(tool().execute("{\"query\":\"q\",\"search_depth\":\"deep\"}").startsWith("error:"), "bad depth");
        assertTrue(tool().execute("{\"query\":\"q\",\"time_range\":\"decade\"}").startsWith("error:"), "bad range");
    }

    @Test
    void apiErrorsSurfaceStatusAndBody() {
        responseStatus = 401;
        responseBody = "{\"detail\":\"invalid api key\"}";

        String result = tool().execute("{\"query\":\"q\"}");

        assertTrue(result.startsWith("error: search API returned HTTP 401"), result);
        assertTrue(result.contains("invalid api key"), result);
    }

    @Test
    void emptyResultsAreReported() {
        responseBody = "{\"query\":\"nothing matches\",\"answer\":null,\"results\":[]}";

        String result = tool().execute("{\"query\":\"nothing matches\"}");

        assertEquals("no results found for query: nothing matches", result);
    }

    @Test
    void longOutputIsTruncatedToTheLimit() {
        String longContent = "0123456789".repeat(20);
        responseBody = """
                {"query":"q","results":[
                  {"title":"t","url":"https://example.com","content":"%s","score":0.5}
                ]}
                """.formatted(longContent);
        WebSearchTool capped = tool(100);

        String result = capped.execute("{\"query\":\"q\"}");

        assertTrue(result.contains("...[output truncated: kept 100 of "), result);
        assertTrue(result.indexOf("01234567890123456789") >= 0, result);
    }

    @Test
    void unreachableServerIsReportedAsErrorText() {
        server.stop(0);
        WebSearchTool dead = new WebSearchTool(new WebSearchTool.Config(
                "test-key", baseUrl, 5, 8000, Duration.ofSeconds(2), true));

        String result = dead.execute("{\"query\":\"q\"}");

        assertTrue(result.startsWith("error: search request failed"), result);
    }
}
