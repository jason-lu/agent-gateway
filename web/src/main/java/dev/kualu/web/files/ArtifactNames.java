package dev.kualu.web.files;

/**
 * Validation of tool-reported artifact names. Both storage backends share it —
 * the sandbox check is repeated at download time regardless of where the bytes
 * live, since the name reaches the gateway through an HTTP URL.
 */
public final class ArtifactNames {

    private ArtifactNames() {
    }

    /**
     * Normalises a relative name ({@code reports/q1.xlsx}) to clean POSIX form.
     *
     * @throws IllegalArgumentException when empty, absolute, or containing {@code ..}
     */
    public static String normalize(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        String posix = relative.trim().replace('\\', '/');
        if (posix.startsWith("/") || posix.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("path must be relative");
        }
        StringBuilder clean = new StringBuilder();
        for (String part : posix.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("path must not contain '..'");
            }
            if (clean.length() > 0) {
                clean.append('/');
            }
            clean.append(part);
        }
        if (clean.length() == 0) {
            throw new IllegalArgumentException("path must not be empty");
        }
        return clean.toString();
    }

    /** @return the last segment of a normalised name — the download's file name */
    public static String fileName(String normalizedName) {
        int slash = normalizedName.lastIndexOf('/');
        return slash < 0 ? normalizedName : normalizedName.substring(slash + 1);
    }
}
