package dev.kualu.llm.moderation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aho-Corasick multi-pattern matcher over UTF-16 chars. Package-private: the
 * lexicon is its only caller.
 */
final class AhoCorasick {

    /** Half-open span in the matched text, plus which compiled pattern hit. */
    record Match(int start, int end, int patternIndex) {
    }

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        final List<Integer> outputs = new ArrayList<>();
    }

    private final Node root = new Node();
    private final String[] patterns;

    private AhoCorasick(String[] patterns) {
        this.patterns = patterns;
    }

    /** Compiles patterns; pattern {@code i} reports back as {@code Match.patternIndex() == i}. */
    static AhoCorasick compile(List<String> patterns) {
        String[] copy = patterns.toArray(new String[0]);
        AhoCorasick automaton = new AhoCorasick(copy);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null || copy[i].isEmpty()) {
                throw new IllegalArgumentException("pattern " + i + " must not be empty");
            }
            Node node = automaton.root;
            for (int c = 0; c < copy[i].length(); c++) {
                node = node.children.computeIfAbsent(copy[i].charAt(c), k -> new Node());
            }
            node.outputs.add(i);
        }
        automaton.buildFailureLinks();
        return automaton;
    }

    private void buildFailureLinks() {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        root.fail = root;
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();
                Node fallback = current.fail;
                while (fallback != root && !fallback.children.containsKey(c)) {
                    fallback = fallback.fail;
                }
                child.fail = fallback.children.getOrDefault(c, root);
                child.outputs.addAll(child.fail.outputs);
                queue.add(child);
            }
        }
    }

    /** All matches in {@code text}; overlapping matches are all reported. */
    List<Match> match(String text) {
        List<Match> matches = new ArrayList<>();
        if (patterns.length == 0 || text.isEmpty()) {
            return matches;
        }
        Node node = root;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            while (node != root && !node.children.containsKey(c)) {
                node = node.fail;
            }
            node = node.children.getOrDefault(c, root);
            for (int patternIndex : node.outputs) {
                int start = i - patterns[patternIndex].length() + 1;
                matches.add(new Match(start, i + 1, patternIndex));
            }
        }
        return matches;
    }
}
