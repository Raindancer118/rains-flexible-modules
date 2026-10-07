package de.raindancer.modules.voicebridge.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A typed {@code /voicechat <sub> …}, split the way SVC reads it: a quoted argument is one argument. */
public record SvcCommand(String sub, List<String> args) {

    public static Optional<SvcCommand> parse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        List<String> words = split(message.strip());
        if (words.size() < 2) {
            return Optional.empty();
        }
        String label = words.getFirst().toLowerCase(Locale.ROOT);
        if (!label.equals("/voicechat") && !label.equals("/voicechat:voicechat")) {
            return Optional.empty();
        }
        return Optional.of(new SvcCommand(words.get(1).toLowerCase(Locale.ROOT), List.copyOf(words.subList(2, words.size()))));
    }

    private static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : text.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == ' ' && !quoted) {
                if (any) {
                    words.add(word.toString());
                    word.setLength(0);
                    any = false;
                }
            } else {
                word.append(c);
                any = true;
            }
        }
        if (any) {
            words.add(word.toString());
        }
        return words;
    }
}
