package com.geekmall.ai;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Detects literal internal prompt echoes, including whitespace changes. */
final class PromptEchoGuard {
    private static final int WINDOW = 12;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?\\r\\n]");

    private final Set<String> fragments = new HashSet<>();
    private final Set<String> phrases = new HashSet<>();

    PromptEchoGuard(String systemPrompt, String... internalLabels) {
        phrases.addAll(Set.of(internalLabels));
        String compactPrompt = compact(systemPrompt);
        for (int i = 0; i + WINDOW <= compactPrompt.length(); i++) {
            fragments.add(compactPrompt.substring(i, i + WINDOW));
        }
        for (String sentence : SENTENCE_END.split(systemPrompt)) {
            String phrase = compact(sentence);
            if (phrase.length() >= 4 && phrase.length() < WINDOW) phrases.add(phrase);
        }
    }

    boolean containsEcho(String text) {
        String compactText = compact(text);
        if (phrases.stream().anyMatch(compactText::contains)) return true;
        for (int i = 0; i + WINDOW <= compactText.length(); i++) {
            if (fragments.contains(compactText.substring(i, i + WINDOW))) return true;
        }
        return false;
    }

    private static String compact(String text) {
        return WHITESPACE.matcher(text).replaceAll("");
    }
}
