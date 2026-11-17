package dev.kualu.llm.moderation;

/** Which side of the conversation a text came from. */
public enum Direction {
    /** User message (typed, or ASR transcript sent through the chat API). */
    INPUT,
    /** Model-generated reply. */
    OUTPUT
}
