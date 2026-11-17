package dev.kualu.llm.memory;

/**
 * The whitelisted personal archive: the only personal fields semantic memory
 * may hold. City is capped at city level — a district or street is a hard
 * identifier and is rejected at the write path.
 */
public record UserProfile(String nickname, Gender gender, String city) {

    public enum Gender { MALE, FEMALE, UNSPECIFIED }
}
