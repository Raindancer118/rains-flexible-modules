package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.Money;

import java.time.Duration;
import java.util.Optional;

/** Whether a mute may be bought off and for how much: a per-hour price, every started hour paid for. */
public final class BuyoffRule implements IModerationRule {

    public static final String OFF = "moderation.buyoff.switched-off";
    public static final String NOT_MUTED = "moderation.buyoff.not-muted";
    public static final String PERMANENT = "moderation.buyoff.permanent";
    public static final String TOO_LONG = "moderation.buyoff.too-long";

    /**
     * @param total     how long the mute was given for, null when it has no end
     * @param remaining what is left of it; empty when it has none or is not in force
     */
    public Verdict mayBuyOff(Money perHour, int longestHours, boolean muted, Duration total, Optional<Duration> remaining) {
        if (perHour == null || !perHour.isPositive()) {
            return Verdict.refused(OFF);
        }
        if (!muted) {
            return Verdict.refused(NOT_MUTED);
        }
        if (total == null || remaining.isEmpty()) {
            return Verdict.refused(PERMANENT);
        }
        if (total.compareTo(Duration.ofHours(Math.max(1, longestHours))) > 0) {
            return Verdict.refused(TOO_LONG, Math.max(1, longestHours));
        }
        return Verdict.allowed();
    }

    /** The price: {@code perHour} for every hour left, a started one counted whole. */
    public Money price(Money perHour, Duration remaining) {
        if (perHour == null || remaining == null || remaining.isNegative() || remaining.isZero()) {
            return Money.ZERO;
        }
        long seconds = remaining.toSeconds() + (remaining.toNanosPart() > 0 ? 1 : 0);
        long hours = (seconds + 3599) / 3600;
        return perHour.times(Math.max(1, hours));
    }

    @Override
    public String describe() {
        return "whether a temporary mute may be bought off, and the price per started hour left";
    }
}
