package dev.kualu.web.mcp;

import java.util.List;
import java.util.Optional;

/** Persistence for user-defined MCP servers. Implementations must isolate by {@code owner}. */
public interface UserMcpServerStore {

    List<UserMcpServer> listByOwner(String owner);

    Optional<UserMcpServer> find(String owner, String name);

    /** Insert or replace the named entry for this owner. */
    void save(String owner, UserMcpServer server);

    boolean delete(String owner, String name);
}
