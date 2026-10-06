package de.raindancer.modules.chat.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.world.time.Times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a poll may be, and how one typed as {@code /poll [length] question | answer | answer} reads.
 * Decides only; {@code service.PollService} runs the poll on Core's {@code Votes}.
 */
public final class PollRule {

    public static final int MOST_ANSWERS = 6;
    public static final int LONGEST_ANSWER = 24;
    public static final int LONGEST_QUESTION = 100;
    public static final Duration SHORTEST = Duration.ofSeconds(10);
    public static final Duration LONGEST = Duration.ofHours(1);

    /** A poll as asked for. */
    public record Request(String question, List<String> answers, Duration lasting) {
    }

    public Parsed<Request> read(String typed, Duration standard) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return Parsed.no("Ask something! /poll [length] question | answer | answer");
        }
        Duration lasting = standard;
        int space = text.indexOf(' ');
        if (space > 0) {
            Optional<Duration> length = Times.parse(text.substring(0, space));
            if (length.isPresent()) {
                lasting = length.get();
                text = text.substring(space + 1).strip();
            }
        }
        List<String> parts = new ArrayList<>(Arrays.stream(text.split("\\|", -1)).map(String::strip).toList());
        String question = parts.removeFirst();
        List<String> answers = parts.isEmpty() ? List.of("Yes", "No") : parts;
        Verdict verdict = check(question, answers, lasting);
        return verdict.isAllowed()
                ? Parsed.ok(new Request(question, List.copyOf(answers), lasting))
                : Parsed.no(sentenceFor(verdict.reason()));
    }

    /** The same limits for a typed poll and one built in the menu. Refusals are message keys. */
    public Verdict check(String question, List<String> answers, Duration lasting) {
        if (question == null || question.isBlank()) {
            return Verdict.refused("chat.poll.no-question");
        }
        if (question.length() > LONGEST_QUESTION) {
            return Verdict.refused("chat.poll.question-too-long");
        }
        if (answers.size() < 2) {
            return Verdict.refused("chat.poll.too-few");
        }
        if (answers.size() > MOST_ANSWERS) {
            return Verdict.refused("chat.poll.too-many");
        }
        Set<String> seen = new HashSet<>();
        for (String answer : answers) {
            if (answer == null || answer.isBlank()) {
                return Verdict.refused("chat.poll.empty-answer");
            }
            if (answer.length() > LONGEST_ANSWER) {
                return Verdict.refused("chat.poll.answer-too-long");
            }
            if (!seen.add(answer.strip().toLowerCase(Locale.ROOT))) {
                return Verdict.refused("chat.poll.twice");
            }
        }
        if (lasting.compareTo(SHORTEST) < 0 || lasting.compareTo(LONGEST) > 0) {
            return Verdict.refused("chat.poll.length");
        }
        return Verdict.allowed();
    }

    /** For the typed form, which answers in one line rather than a message key. */
    private static String sentenceFor(String key) {
        return switch (key) {
            case "chat.poll.no-question" -> "Ask something! /poll [length] question | answer | answer";
            case "chat.poll.question-too-long" -> "That question is a speech — " + LONGEST_QUESTION + " characters at most.";
            case "chat.poll.too-few" -> "A poll needs at least two answers, otherwise it's a statement.";
            case "chat.poll.too-many" -> "At most " + MOST_ANSWERS + " answers — nobody reads past that anyway.";
            case "chat.poll.empty-answer" -> "One of those answers is empty.";
            case "chat.poll.answer-too-long" -> "Keep each answer to " + LONGEST_ANSWER + " characters, so it fits on a button.";
            case "chat.poll.twice" -> "The same answer twice would split the vote.";
            default -> "Between 10 seconds and an hour, please.";
        };
    }

    /** A bar {@code width} long, filled to {@code share} (0–1). */
    public static String bar(double share, int width) {
        int filled = Double.isNaN(share) ? 0 : (int) Math.round(Math.max(0, Math.min(1, share)) * width);
        return "█".repeat(filled) + "░".repeat(width - filled);
    }
}
