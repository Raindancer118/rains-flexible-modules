package de.raindancer.modules.playerutils.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One thing an action can be told besides who: a number, one word out of a few, or a switch.
 *
 * <p>Arguments are recognised by what they are rather than where they stand, so {@code /damage 3 Lilly}
 * and {@code /damage Lilly 3} both work — nobody should have to remember which way round a command wants it.
 *
 * @param name     what it is called in usage lines and errors
 * @param kind     how it is recognised
 * @param words    the words a {@link Kind#WORD} or {@link Kind#SWITCH} accepts, first one canonical
 * @param min      a number's lowest
 * @param max      a number's highest
 * @param fallback a number's value when none is given; NaN means it must be given
 */
public record Parameter(String name, Kind kind, List<String> words, double min, double max, double fallback) {

    public enum Kind {
        /** A number in a range. */
        NUMBER,
        /** One of a few words; the first word is the default when none is given. */
        WORD,
        /** Present or not. */
        SWITCH,
        /** Everything left, as text — sudo's command. */
        REST
    }

    public Parameter {
        words = words == null ? List.of() : List.copyOf(words);
    }

    public static Parameter number(String name, double min, double max, double fallback) {
        return new Parameter(name, Kind.NUMBER, List.of(), min, max, fallback);
    }

    public static Parameter required(String name, double min, double max) {
        return new Parameter(name, Kind.NUMBER, List.of(), min, max, Double.NaN);
    }

    public static Parameter word(String name, String... words) {
        return new Parameter(name, Kind.WORD, List.of(words), 0, 0, 0);
    }

    public static Parameter flag(String word) {
        return new Parameter(word, Kind.SWITCH, List.of(word), 0, 0, 0);
    }

    public static Parameter rest(String name) {
        return new Parameter(name, Kind.REST, List.of(), 0, 0, 0);
    }

    public boolean isRequired() {
        return kind == Kind.NUMBER && Double.isNaN(fallback) || kind == Kind.REST;
    }

    /** Whether {@code token} is a word this parameter takes, in any case. */
    public Optional<String> wordFor(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String lowered = token.toLowerCase(Locale.ROOT);
        return words.stream().filter(word -> word.equals(lowered)).findFirst();
    }

    /** How it looks in a usage line: {@code [hearts]}, {@code <size>}, {@code [up|forward|look]}. */
    public String usage() {
        String inner = switch (kind) {
            case NUMBER, REST -> name;
            case WORD -> String.join("|", words);
            case SWITCH -> words.getFirst();
        };
        return isRequired() ? "<" + inner + ">" : "[" + inner + "]";
    }
}
