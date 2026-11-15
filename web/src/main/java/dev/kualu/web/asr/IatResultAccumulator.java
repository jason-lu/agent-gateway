package dev.kualu.web.asr;

import tools.jackson.databind.JsonNode;

import java.util.TreeMap;

/**
 * Accumulates 讯飞 IAT result frames into full draft text, honouring the wpgs
 * dynamic-revision protocol ({@code dwa:"wpgs"}): frames arrive out of order and
 * may retroactively replace a previous span via {@code pgs:"rpl"} + {@code rg}.
 *
 * <p>Package-private on purpose — this is the protocol's core logic, extracted
 * so it can be tested without a network.
 */
class IatResultAccumulator {

    private final TreeMap<Integer, String> segments = new TreeMap<>();

    /** One result object: its sentence number, revision hint, and concatenated text. */
    record Frame(int sn, String pgs, int[] rg, String text) {
    }

    /**
     * Parses one {@code data.result} object. Frame text is the concatenation of
     * each word's first candidate ({@code ws[].cw[0].w}); absent pieces degrade
     * to empty rather than failing the stream.
     */
    static Frame parse(JsonNode result) {
        StringBuilder text = new StringBuilder();
        JsonNode ws = result.path("ws");
        if (ws.isArray()) {
            for (JsonNode word : ws) {
                JsonNode candidates = word.path("cw");
                if (candidates.isArray() && !candidates.isEmpty()) {
                    text.append(candidates.path(0).path("w").asString(""));
                }
            }
        }
        return new Frame(
                result.path("sn").asInt(0),
                result.path("pgs").asString(""),
                parseRange(result.path("rg")),
                text.toString());
    }

    /**
     * Applies one frame with wpgs revision semantics and returns the full text so
     * far: an {@code rpl} frame first drops the {@code rg} span (inclusive), then
     * the frame's own text is stored under its {@code sn}.
     */
    String accept(Frame frame) {
        if ("rpl".equals(frame.pgs()) && frame.rg() != null) {
            for (int sn = frame.rg()[0]; sn <= frame.rg()[1]; sn++) {
                segments.remove(sn);
            }
        }
        segments.put(frame.sn(), frame.text());
        return fullText();
    }

    /** Every segment's text in sentence-number order — the consumer's full draft. */
    String fullText() {
        StringBuilder text = new StringBuilder();
        for (String segment : segments.values()) {
            text.append(segment);
        }
        return text.toString();
    }

    /** @return {@code rg} as {@code [from, to]}, or {@code null} when absent/malformed */
    private static int[] parseRange(JsonNode rg) {
        if (rg.isArray() && rg.size() >= 2) {
            return new int[]{rg.path(0).asInt(0), rg.path(1).asInt(0)};
        }
        return null;
    }
}
