package dev.kualu.llm.moderation;

/** Enforcement mode. {@link #FLAG} is the gray-release mode: audit only, never block. */
public enum Mode {
    BLOCK,
    FLAG
}
