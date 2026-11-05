package dev.kualu.mcp;

import com.google.gson.JsonObject;

import java.time.Duration;

/** Invokes a remote MCP tool; satisfied by {@link McpSession#callTool}. */
@FunctionalInterface
public interface McpToolCaller {

    McpCallResult call(String name, JsonObject arguments, Duration timeout);
}
