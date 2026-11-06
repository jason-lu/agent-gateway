package dev.kualu.web.session;

/**
 * Reference to an externally stored message image: {@code ref:<sessionId>/<imageId>}.
 * Only {@code data:} images are externalised; http(s) URLs and data URIs in live
 * memory pass through untouched. The image content is content-addressed by
 * SHA-256 hex id, so re-persisting the same image never duplicates the row.
 */
public final class ImageRef {

    private static final String PREFIX = "ref:";

    private ImageRef() {
    }

    public static boolean isRef(String image) {
        return image != null && image.startsWith(PREFIX);
    }

    public static String format(String sessionId, String imageId) {
        return PREFIX + sessionId + "/" + imageId;
    }

    public static String sessionIdOf(String ref) {
        return split(ref)[0];
    }

    public static String imageIdOf(String ref) {
        return split(ref)[1];
    }

    private static String[] split(String ref) {
        String body = ref.substring(PREFIX.length());
        int slash = body.indexOf('/');
        if (slash <= 0 || slash == body.length() - 1) {
            throw new IllegalArgumentException("malformed image ref: " + ref);
        }
        return new String[] {body.substring(0, slash), body.substring(slash + 1)};
    }
}
