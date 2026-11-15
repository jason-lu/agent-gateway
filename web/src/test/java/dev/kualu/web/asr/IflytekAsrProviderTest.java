package dev.kualu.web.asr;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IflytekAsrProviderTest {

    private static final Instant T1 = Instant.parse("2025-06-10T12:00:00Z");
    private static final Instant T2 = Instant.parse("2025-06-11T08:30:00Z");

    @Test
    void signIsDeterministicInSecretAndDate() {
        String date = date(T1);
        String first = IflytekAsrProvider.sign("the-secret", date);
        assertEquals(first, IflytekAsrProvider.sign("the-secret", date));
        // A different date must produce a different signature.
        assertNotEquals(first, IflytekAsrProvider.sign("the-secret", date(T2)));
    }

    @Test
    void signOutputIsBase64OfSha256() {
        byte[] decoded = Base64.getDecoder().decode(IflytekAsrProvider.sign("the-secret", date(T1)));
        // HMAC-SHA256 digests are 32 bytes; a valid base64 decode of that length is the check.
        assertEquals(32, decoded.length);
    }

    @Test
    void buildUriCarriesAuthorizationDateAndHostParams() {
        URI uri = IflytekAsrProvider.buildUri("the-key", "the-secret", T1);

        assertEquals("wss", uri.getScheme());
        assertEquals(IflytekAsrProvider.HOST, uri.getHost());
        assertEquals("/v1/iat", uri.getPath());

        Map<String, String> params = queryParams(uri);
        String date = date(T1);
        assertEquals(date, params.get("date"));
        assertEquals(IflytekAsrProvider.HOST, params.get("host"));

        String authorization = params.get("authorization");
        // The query param is base64 of the authorization origin (iFlytek's official shape).
        String origin = new String(Base64.getDecoder().decode(authorization), StandardCharsets.UTF_8);
        assertTrue(origin.contains("api_key=\"the-key\""));
        assertTrue(origin.contains("algorithm=\"hmac-sha256\""));
        assertTrue(origin.contains("headers=\"host date request-line\""));
        assertTrue(origin.contains("signature=\"" + IflytekAsrProvider.sign("the-secret", date) + "\""));
    }

    private static String date(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
    }

    /** Query params decoded the way a form-style decoder would read them (URLEncoder output). */
    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }
}
