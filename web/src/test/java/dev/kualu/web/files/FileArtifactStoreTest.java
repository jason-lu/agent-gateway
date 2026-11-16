package dev.kualu.web.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileArtifactStoreTest {

    @TempDir
    Path temp;

    private FileArtifactStore store() {
        return new FileArtifactStore(temp.resolve("out"));
    }

    @Test
    void resolvesNestedRelativeName() {
        FileArtifactStore store = store();
        Path resolved = store.resolve("reports/q1.xlsx");
        assertThat(resolved).isEqualTo(store.root().resolve("reports").resolve("q1.xlsx"));
    }

    @Test
    void normalisesBackslashes() {
        FileArtifactStore store = store();
        assertThat(store.resolve("reports\\q1.xlsx")).isEqualTo(store.resolve("reports/q1.xlsx"));
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> store().resolve("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsolutePaths() {
        FileArtifactStore store = store();
        assertThatThrownBy(() -> store.resolve("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.resolve("C:\\Windows\\win.ini"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDotDot() {
        FileArtifactStore store = store();
        assertThatThrownBy(() -> store.resolve("../secret.xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.resolve("reports/../../secret.xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootIsCreated() {
        FileArtifactStore store = store();
        assertThat(store.root()).isDirectory();
    }
}
