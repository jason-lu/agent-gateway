package dev.kualu.web.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePropertiesTest {

    private static FileProperties.Oss oss(String endpoint, String bucket, String ak, String sk) {
        return new FileProperties.Oss(endpoint, bucket, "office/", ak, sk);
    }

    @Test
    void blankStoreMeansLocal() {
        assertThat(new FileProperties("", "output/office", null).store()).isEqualTo("local");
        assertThat(new FileProperties(null, "output/office", null).store()).isEqualTo("local");
        assertThat(new FileProperties("local", "output/office", null).store()).isEqualTo("local");
    }

    @Test
    void rejectsUnknownStore() {
        assertThatThrownBy(() -> new FileProperties("s3", "output/office", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ossStoreRequiresConnection() {
        assertThatThrownBy(() -> new FileProperties("oss", "output/office",
                oss("", "kailab", "ak", "sk")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileProperties("oss", "output/office",
                oss("https://oss-cn-shanghai.aliyuncs.com", "kailab", "ak", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ossStoreAcceptsFullConnection() {
        FileProperties properties = new FileProperties("oss", "output/office",
                oss("https://oss-cn-shanghai.aliyuncs.com", "kailab", "ak", "sk"));
        assertThat(properties.oss().bucket()).isEqualTo("kailab");
        assertThat(properties.oss().prefix()).isEqualTo("office/");
    }
}
