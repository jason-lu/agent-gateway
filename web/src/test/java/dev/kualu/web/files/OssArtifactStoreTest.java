package dev.kualu.web.files;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssArtifactStoreTest {

    private final OSS client = mock(OSS.class);
    private final OssArtifactStore store = new OssArtifactStore(client, "kailab", "office/");

    @Test
    void keysArePrefixPlusNormalizedName() {
        assertThat(store.keyOf("reports/q1.xlsx")).isEqualTo("office/reports/q1.xlsx");
        assertThat(new OssArtifactStore(client, "kailab", "office").keyOf("a.xlsx"))
                .isEqualTo("office/a.xlsx");
        assertThat(new OssArtifactStore(client, "kailab", "").keyOf("a.xlsx")).isEqualTo("a.xlsx");
    }

    @Test
    void rejectsEscapingNamesBeforeTouchingOss() {
        assertThatThrownBy(() -> store.keyOf("../secret.xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.exists("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void existsDelegatesToClient() {
        when(client.doesObjectExist("kailab", "office/a.xlsx")).thenReturn(true);
        assertThat(store.exists("a.xlsx")).isTrue();
        assertThat(store.exists("b.xlsx")).isFalse();
    }

    @Test
    void openStreamsTheObject() throws Exception {
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(client.getObject("kailab", "office/a.xlsx")).thenReturn(object);
        try (InputStream in = store.open("a.xlsx")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void closeShutsTheClient() {
        store.close();
        verify(client).shutdown();
    }
}
