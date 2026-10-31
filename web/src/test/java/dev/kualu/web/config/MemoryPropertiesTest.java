package dev.kualu.web.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryPropertiesTest {

    @Test
    void validThresholdsAreAccepted() {
        new MemoryProperties(true, 20, 8, null);
        new MemoryProperties(true, 5, 2, null);
    }

    @Test
    void invalidThresholdsFailFastWhenEnabled() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryProperties(true, 20, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryProperties(true, 20, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryProperties(true, 4, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryProperties(true, -1, 2, null));
    }

    @Test
    void disabledSkipsValidation() {
        // Matches CLI: a disabled feature must not block startup on unused values.
        new MemoryProperties(false, -1, 0, null);
    }
}
