package dev.kualu.llm.moderation;

/**
 * Closed set of violation categories. The lexicon and review data are keyed by
 * these codes; wording and examples live in the ops-maintained lexicon file,
 * never in code.
 */
public enum Category {
    /** 暴力恐怖: violence, terrorism, extremism. */
    VIOLENCE,
    /** 危害国家安全与公共利益 (compliance category, see the spec). */
    REACTIONARY,
    /** 反人类与仇恨: hate speech, discrimination, incitement. */
    ANTI_HUMANITY,
    /** 违法犯罪: drugs, gambling, weapons, crime instruction. */
    CRIME,
    /** 诈骗与黑产: fraud, phishing, personal-data trading. */
    FRAUD,
    /** 自伤自杀: self-harm — only incitement/instruction, never help-seeking. */
    SELF_HARM,
    /** 辱骂与低俗: abuse, vulgarity. */
    ABUSE,
    /** No violation found. */
    NONE;

    /** Parses a lexicon JSON key; {@link #NONE} for unknown codes. */
    public static Category parse(String code) {
        if (code == null) {
            return NONE;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
