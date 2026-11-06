package dev.kualu.web.session;

import java.time.Instant;

/**
 * Lightweight session row for listings: identity plus a message count, never
 * the conversation payload. Loading every session's messages just to render a
 * list is what made first access slow.
 */
public record SessionSummary(String id, String owner, Instant createdAt, int messageCount) {
}
