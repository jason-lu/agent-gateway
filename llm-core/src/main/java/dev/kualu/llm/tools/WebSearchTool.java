package dev.kualu.llm.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import dev.kualu.http.HttpClient;
import dev.kualu.http.HttpClientException;
import dev.kualu.llm.AgentTool;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Web search through the Tavily search API ({@code POST /search}): one query in,
 * the top results (title / url / snippet) plus an optional generated answer out.
 *
 * <p>Bad arguments, API errors and empty result sets are returned as result text
 * so the model can react; only unexpected faults throw. The response is truncated
 * to the configured limit so one search cannot flood the conversation.
 */
public final class WebSearchTool implements AgentTool {

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "What to search for: a keyword phrase or a natural-language question."
                },
                "max_results": {
                  "type": "integer",
                  "description": "How many results to return (1-10). Defaults to the server default."
                },
                "topic": {
                  "type": "string",
                  "enum": ["general", "news"],
                  "description": "general for evergreen topics, news for recent events."
                },
                "search_depth": {
                  "type": "string",
                  "enum": ["basic", "advanced"],
                  "description": "basic is fast and cheap; advanced digs deeper and costs more credits."
                },
                "time_range": {
                  "type": "string",
                  "enum": ["day", "week", "month", "year"],
                  "description": "Only return results published within this window."
                }
              },
              "required": ["query"]
            }
            """;

    private static final int MAX_RESULTS_CAP = 10;
    private static final int ERROR_BODY_SNIPPET = 500;

    /** Search limits; see the constructor for the constraints. */
    public record Config(String apiKey, String baseUrl, int maxResults, int outputLimit,
                         Duration timeout, boolean includeAnswer) {

        public static final String DEFAULT_BASE_URL = "https://api.tavily.com";
        public static final int DEFAULT_MAX_RESULTS = 5;
        public static final int DEFAULT_OUTPUT_LIMIT = 8000;
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

        public Config {
            Objects.requireNonNull(apiKey, "apiKey");
            if (apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = DEFAULT_BASE_URL;
            }
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            if (maxResults <= 0) {
                throw new IllegalArgumentException("maxResults must be positive");
            }
            if (outputLimit <= 0) {
                throw new IllegalArgumentException("outputLimit must be positive");
            }
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }

        /** Defaults: Tavily endpoint, 5 results, 8000-character cap, 30 s timeout, answer on. */
        public static Config of(String apiKey) {
            return new Config(apiKey, DEFAULT_BASE_URL, DEFAULT_MAX_RESULTS,
                    DEFAULT_OUTPUT_LIMIT, DEFAULT_TIMEOUT, true);
        }
    }

    private final Config config;
    private final HttpClient http;

    public WebSearchTool(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = new HttpClient(config.timeout());
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web (Tavily) and return top results with title, url and a content "
                + "snippet, plus a short generated answer when available. Use it for facts, news "
                + "and anything the model does not know or that may have changed. Quote the result "
                + "urls when the answer depends on them.";
    }

    @Override
    public String parametersJson() {
        return PARAMETERS_JSON;
    }

    @Override
    public String execute(String argumentsJson) {
        JsonObject args;
        try {
            JsonElement parsed = argumentsJson == null || argumentsJson.isBlank()
                    ? null
                    : JsonParser.parseString(argumentsJson);
            if (parsed == null || !parsed.isJsonObject()) {
                return "error: arguments must be a JSON object";
            }
            args = parsed.getAsJsonObject();
        } catch (JsonSyntaxException ex) {
            return "error: arguments were not valid JSON: " + ex.getMessage();
        }

        String query = stringArg(args, "query");
        if (query == null || query.isBlank()) {
            return "error: missing required argument 'query'";
        }

        Integer maxResults = intArg(args, "max_results");
        if (maxResults == null && args.get("max_results") != null && !args.get("max_results").isJsonNull()) {
            return "error: 'max_results' must be an integer";
        }
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS_CAP)) {
            return "error: 'max_results' must be between 1 and " + MAX_RESULTS_CAP;
        }

        String topic = enumArg(args, "topic", "general", "news");
        if (topic == null && hasNonNull(args, "topic")) {
            return "error: 'topic' must be one of: general, news";
        }
        String depth = enumArg(args, "search_depth", "basic", "advanced");
        if (depth == null && hasNonNull(args, "search_depth")) {
            return "error: 'search_depth' must be one of: basic, advanced";
        }
        String timeRange = enumArg(args, "time_range", "day", "week", "month", "year");
        if (timeRange == null && hasNonNull(args, "time_range")) {
            return "error: 'time_range' must be one of: day, week, month, year";
        }

        return search(buildBody(query,
                maxResults != null ? maxResults : config.maxResults(),
                topic, depth, timeRange));
    }

    private String buildBody(String query, int maxResults, String topic, String depth, String timeRange) {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("max_results", maxResults);
        body.addProperty("include_answer", config.includeAnswer());
        body.addProperty("include_raw_content", false);
        if (topic != null) {
            body.addProperty("topic", topic);
        }
        if (depth != null) {
            body.addProperty("search_depth", depth);
        }
        if (timeRange != null) {
            body.addProperty("time_range", timeRange);
        }
        return body.toString();
    }

    private String search(String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.apiKey());

        HttpResponse<String> response;
        try {
            response = http.post(config.baseUrl() + "/search", headers, body);
        } catch (HttpClientException ex) {
            return "error: search request failed: " + ex.getMessage();
        }

        int status = response.statusCode();
        String text = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            return "error: search API returned HTTP " + status + ": " + snippet(text);
        }

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return "error: search API returned a non-JSON body";
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException ex) {
            return "error: search API returned a malformed JSON body: " + ex.getMessage();
        }
        return format(root);
    }

    private String format(JsonObject root) {
        StringBuilder out = new StringBuilder();

        String answer = stringOf(root.get("answer"));
        if (answer != null && !answer.isBlank()) {
            out.append("answer: ").append(answer.strip()).append("\n\n");
        }

        JsonElement resultsElement = root.get("results");
        if (resultsElement == null || !resultsElement.isJsonArray()) {
            return out.length() == 0 ? "no results" : out.toString().strip();
        }
        JsonArray results = resultsElement.getAsJsonArray();
        if (results.isEmpty()) {
            return out.length() == 0
                    ? "no results found for query: " + stringOf(root.get("query"))
                    : out.toString().strip();
        }

        int index = 1;
        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject result = element.getAsJsonObject();
            String title = stringOf(result.get("title"));
            String url = stringOf(result.get("url"));
            String content = stringOf(result.get("content"));
            String score = stringOf(result.get("score"));

            out.append('[').append(index++).append("] ")
                    .append(title == null || title.isBlank() ? "(untitled)" : title.strip());
            if (score != null && !score.isBlank()) {
                out.append(" (score ").append(score.strip()).append(')');
            }
            out.append('\n');
            if (url != null && !url.isBlank()) {
                out.append(url.strip()).append('\n');
            }
            if (content != null && !content.isBlank()) {
                out.append(content.strip()).append('\n');
            }
            out.append('\n');
        }
        return truncate(out.toString().strip());
    }

    private String truncate(String text) {
        if (text.length() <= config.outputLimit()) {
            return text;
        }
        int kept = config.outputLimit();
        return text.substring(0, kept)
                + "\n...[output truncated: kept " + kept + " of " + text.length() + " characters]";
    }

    private static String snippet(String text) {
        String value = text.strip();
        if (value.length() <= ERROR_BODY_SNIPPET) {
            return value;
        }
        return value.substring(0, ERROR_BODY_SNIPPET) + "...";
    }

    private static String stringOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static String stringArg(JsonObject args, String key) {
        return stringOf(args.get(key));
    }

    /** {@code null} both when absent and when present-but-not-an-integer; see {@link #hasNonNull}. */
    private static Integer intArg(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException ex) {
            return null;
        }
    }

    /** Returns the value when it is one of {@code allowed}, otherwise {@code null}. */
    private static String enumArg(JsonObject args, String key, String... allowed) {
        String value = stringArg(args, key);
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String option : allowed) {
            if (option.equals(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private static boolean hasNonNull(JsonObject args, String key) {
        JsonElement element = args.get(key);
        return element != null && !element.isJsonNull();
    }
}
