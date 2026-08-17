package de.raindancer.modules.chat.rules;

import de.raindancer.core.platform.rule.Verdict;

/**
 * Whether a chat line may go out, judged on its own text and on how long ago the last one was —
 * nothing here touches a player, a server or a clock. A caller reads {@link System#currentTimeMillis()}
 * once and hands the difference in.
 */
public final class ChatQualityRules {

    private ChatQualityRules() {
    }

    /**
     * Refuses a message that is mostly capital letters, once it has enough letters in it for that to
     * mean something — {@code "NO"} is not somebody shouting, and a three-letter refusal would make
     * the filter the thing players actually notice.
     */
    public static Verdict caps(String text, int thresholdPercent, int minLength) {
        if (text == null) {
            return Verdict.allowed();
        }
        String letters = text.replaceAll("[^\\p{L}]", "");
        if (letters.length() < minLength) {
            return Verdict.allowed();
        }
        long upper = letters.chars().filter(Character::isUpperCase).count();
        int percent = (int) Math.round(upper * 100.0 / letters.length());
        return percent >= thresholdPercent ? Verdict.refused("chat.quality.caps") : Verdict.allowed();
    }

    /** Refuses sending the exact same message twice in a row. */
    public static Verdict repeat(String text, String lastMessage) {
        if (text == null || lastMessage == null) {
            return Verdict.allowed();
        }
        return text.equals(lastMessage) ? Verdict.refused("chat.quality.repeat") : Verdict.allowed();
    }

    /** The ordinary per-player cooldown between messages. */
    public static Verdict cooldown(long millisSinceLast, int cooldownSeconds) {
        return wait(millisSinceLast, cooldownSeconds, "chat.quality.cooldown");
    }

    /**
     * A server-wide cooldown on top of {@link #cooldown} — the one {@code /chat slowmode} raises for
     * a busy moment, separately from whatever an owner has set as the everyday default.
     */
    public static Verdict slowmode(long millisSinceLast, int slowmodeSeconds) {
        return wait(millisSinceLast, slowmodeSeconds, "chat.quality.slowmode");
    }

    private static Verdict wait(long millisSinceLast, int seconds, String reasonKey) {
        if (seconds <= 0) {
            return Verdict.allowed();
        }
        long remaining = seconds * 1000L - millisSinceLast;
        return remaining > 0 ? Verdict.refused(reasonKey, secondsLeft(remaining)) : Verdict.allowed();
    }

    private static long secondsLeft(long millisRemaining) {
        return (millisRemaining + 999) / 1000;
    }
}
