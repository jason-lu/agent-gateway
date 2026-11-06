package dev.kualu.llm.client;

/**
 * Materialises message images before they are sent to a vision model: a store
 * reference becomes an inline {@code data:} URI, while already-usable images
 * (data URIs, http(s) URLs) pass through unchanged.
 */
@FunctionalInterface
public interface ImageSource {

    String materialize(String image);

    /** No-op source: images are sent exactly as they appear in the message. */
    static ImageSource passThrough() {
        return image -> image;
    }
}
