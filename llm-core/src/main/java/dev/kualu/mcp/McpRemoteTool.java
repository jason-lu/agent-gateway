package dev.kualu.mcp;

import com.google.gson.JsonObject;

import java.util.Objects;

/** A tool advertised by an MCP server ({@code tools/list} entry). */
public record McpRemoteTool(String name, String description, JsonObject inputSchema) {

    public McpRemoteTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema");
        description = description == null ? "" : description;
    }
}
