package dev.kualu.llm.memory;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardIdentifierDetectorTest {

    private final HardIdentifierDetector detector = new HardIdentifierDetector();

    // ---- positive: each category hits ----

    @Test
    void mobileNumber_isPhone() {
        assertEquals(HardIdentifierDetector.PHONE, hit("我的手机号是13812345678"));
    }

    @Test
    void separatedMobile_isPhone() {
        assertEquals(HardIdentifierDetector.PHONE, hit("电话 138-1234-5678"));
        assertEquals(HardIdentifierDetector.PHONE, hit("电话 138 1234 5678"));
        assertEquals(HardIdentifierDetector.PHONE, hit("电话 138-12345678"));
        assertEquals(HardIdentifierDetector.PHONE, hit("电话 138 12345678"));
    }

    @Test
    void landline_isPhone() {
        assertEquals(HardIdentifierDetector.PHONE, hit("座机 010-12345678"));
    }

    @Test
    void email_isEmail() {
        assertEquals(HardIdentifierDetector.EMAIL, hit("联系我 jason@gmail.com 谢谢"));
    }

    @Test
    void idCard_withValidChecksum_isIdCard() {
        // 110105 19491231 002X — a classic valid sample.
        assertEquals(HardIdentifierDetector.ID_CARD, hit("身份证 11010519491231002X"));
    }

    @Test
    void bankCard_withValidLuhn_isBankCard() {
        // 4111111111111111 — the standard Luhn-valid test number.
        assertEquals(HardIdentifierDetector.BANK_CARD, hit("卡号 4111111111111111"));
    }

    @Test
    void buildingNumber_isAddress() {
        assertEquals(HardIdentifierDetector.ADDRESS, hit("我住 12 号楼"));
    }

    @Test
    void streetWithNumber_isAddress() {
        assertEquals(HardIdentifierDetector.ADDRESS, hit("地址是文一西路100号"));
    }

    @Test
    void districtName_isAddress() {
        assertEquals(HardIdentifierDetector.ADDRESS, hit("我在西湖区上班"));
    }

    @Test
    void credentialValue_isCredential() {
        assertEquals(HardIdentifierDetector.CREDENTIAL, hit("密码: mySecret123"));
    }

    @Test
    void credentialWithoutDelimiter_isCredential() {
        assertEquals(HardIdentifierDetector.CREDENTIAL, hit("密码是abc123"));
        assertEquals(HardIdentifierDetector.CREDENTIAL, hit("password is mySecret"));
    }

    @Test
    void townAndBareStreet_areAddress() {
        assertEquals(HardIdentifierDetector.ADDRESS, hit("我住在良渚街道"));
        assertEquals(HardIdentifierDetector.ADDRESS, hit("我住在长安镇"));
        assertEquals(HardIdentifierDetector.ADDRESS, hit("我住文一西路"));
    }

    @Test
    void longUnexplainedDigitRun_isUnknown() {
        // 12 digits, not Luhn, not a timestamp.
        assertEquals(HardIdentifierDetector.UNKNOWN, hit("账号 123456789012"));
    }

    // ---- negatives: ordinary text stays clean ----

    @Test
    void technicalFacts_areClean() {
        assertClean("服务部署在 K8s 上，日志看 Loki");
        assertClean("以后报告默认用中文写");
        assertClean("我用 Java 21 和 Spring Boot 3");
        assertClean("记住：密码要定期更换");
    }

    @Test
    void genericPlaceWords_areNotAddress() {
        assertClean("这个小镇很安静");
        assertClean("这条思路行不通");
        assertClean("乡镇企业调研");
    }

    @Test
    void zoneLikeCompounds_areNotAddress() {
        assertClean("服务部署在开发区的机房");
        assertClean("这个园区的网络不错");
    }

    @Test
    void timestamps_areNotUnknown() {
        assertClean("构建时间 20261117235526 完成");
    }

    @Test
    void shortNumbers_areClean() {
        assertClean("端口 8080，超时 30 秒");
        assertClean("2026 年 11 月上线");
    }

    @Test
    void cityName_isClean() {
        // City-level is allowed; only district-and-below is an address.
        assertClean("我在杭州工作");
    }

    @Test
    void idCardWithBadChecksum_failsClosedButIsNotIdCard() {
        // Same shape as a valid ID, wrong check digit: not classified as id-card,
        // but the 18-digit run is still flagged (fail closed).
        Optional<HardIdentifierDetector.Hit> found = detector.scan("编号 110105194912310021");
        assertTrue(found.isPresent());
        assertTrue(!HardIdentifierDetector.ID_CARD.equals(found.get().category()),
                "bad checksum must not classify as id-card");
    }

    // ---- fail-closed ----

    @Test
    void fullWidthDigits_areNormalized() {
        assertEquals(HardIdentifierDetector.PHONE, hit("手机 １３８１２３４５６７８"));
    }

    @Test
    void blankText_isClean() {
        assertTrue(detector.scan("").isEmpty());
        assertTrue(detector.scan("   ").isEmpty());
        assertTrue(detector.scan(null).isEmpty());
    }

    @Test
    void hit_carriesCategoryOnly() {
        Optional<HardIdentifierDetector.Hit> hit = detector.scan("手机13812345678");
        assertTrue(hit.isPresent());
        assertEquals(HardIdentifierDetector.PHONE, hit.get().category());
    }

    // ---- helpers ----

    private String hit(String text) {
        return detector.scan(text).orElseThrow(() -> new AssertionError("expected hit: " + text)).category();
    }

    private void assertClean(String text) {
        assertTrue(detector.scan(text).isEmpty(), "expected clean: " + text);
    }
}
