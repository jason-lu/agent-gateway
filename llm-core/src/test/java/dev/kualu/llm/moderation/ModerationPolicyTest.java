package dev.kualu.llm.moderation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationPolicyTest {

    private final ModerationPolicy block = new ModerationPolicy(Mode.BLOCK);
    private final ModerationPolicy flag = new ModerationPolicy(Mode.FLAG);

    @Test
    void blockModeMatrix() {
        assertEquals(Action.BLOCK, block.actionFor(Severity.HIGH));
        assertEquals(Action.BLOCK, block.actionFor(Severity.MEDIUM));
        assertEquals(Action.REDACT, block.actionFor(Severity.LOW));
    }

    @Test
    void flagModeNeverIntercepts() {
        assertEquals(Action.FLAG, flag.actionFor(Severity.HIGH));
        assertEquals(Action.FLAG, flag.actionFor(Severity.MEDIUM));
        assertEquals(Action.FLAG, flag.actionFor(Severity.LOW));
    }

    @Test
    void reviewRules() {
        assertTrue(block.needsReview(Severity.MEDIUM));
        assertFalse(block.needsReview(Severity.HIGH));
        assertFalse(block.needsReview(Severity.LOW));
    }

    @Test
    void flagFindingsAlwaysNeedReview() {
        ModerationHit unsure = new ModerationHit(Category.VIOLENCE, Severity.HIGH, Action.FLAG, "x", List.of());
        ModerationHit blocked = new ModerationHit(Category.VIOLENCE, Severity.HIGH, Action.BLOCK, "x", List.of());
        assertTrue(block.needsReview(unsure));
        assertFalse(block.needsReview(blocked));
    }

    @Test
    void redactMasksEverySpan() {
        ModerationHit hit = new ModerationHit(Category.ABUSE, Severity.LOW, Action.REDACT, "b***d",
                List.of(new ModerationHit.Span(1, 2), new ModerationHit.Span(3, 4)));
        assertEquals("a***c***", hit.redact("abcd"));
    }

    @Test
    void excerptMasksTheMiddle() {
        assertEquals("法***功", ModerationHit.excerptOf("法轮功", new ModerationHit.Span(0, 3)));
        assertEquals("好", ModerationHit.excerptOf("好", new ModerationHit.Span(0, 1)));
    }
}
