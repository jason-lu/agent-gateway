package dev.kualu.mcp;

/**
 * Test fixture for {@link StdioTransportTest}: prints one JSON-RPC result
 * carrying the {@code MCP_ENV_PROBE} value, proving the configured environment
 * reaches the child process.
 */
public final class EnvEcho {

    public static void main(String[] args) {
        String value = System.getenv().getOrDefault("MCP_ENV_PROBE", "<unset>");
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + value + "\"}");
    }

    private EnvEcho() {
    }
}
