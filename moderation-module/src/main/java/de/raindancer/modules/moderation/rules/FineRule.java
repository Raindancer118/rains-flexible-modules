package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What a fine comes to, and who may hand out how much of one — arithmetic only; charging is {@code FineService}'s.
 */
public final class FineRule implements IModerationRule {

    public static final String TOO_MUCH = "moderation.fine.too-much-for-you";

    /** How a fine splits between the victim and the server. */
    public record Split(Money victim, Money server) {
    }

    /** The amounts written as "50, 100, 250", skipping what is not an amount. */
    public static List<Money> ladder(String written, Function<String, Money> parse) {
        List<Money> amounts = new ArrayList<>();
        if (written == null || written.isBlank()) {
            return amounts;
        }
        for (String part : written.split(",")) {
            Money amount = parse.apply(part.strip());
            if (amount != null && amount.isPositive()) {
                amounts.add(amount);
            }
        }
        return amounts;
    }

    /**
     * What the {@code nth} warning inside the window costs: the ladder's rung (the last one repeats) or the
     * share of the balance, whichever is more. The share is held between {@code least} and {@code most}; a
     * bound of zero is no bound.
     *
     * @param nth one for the first warning in the window
     * @return zero when warnings cost nothing
     */
    public Money warnFine(int nth, Money balance, List<Money> ladder, int percent, Money least, Money most) {
        Money rung = ladder == null || ladder.isEmpty() ? Money.ZERO
                : ladder.get(Math.min(Math.max(nth, 1), ladder.size()) - 1);
        Money share = Money.ZERO;
        if (percent > 0) {
            share = (balance == null ? Money.ZERO : balance.max(Money.ZERO)).share(percent / 100.0);
            if (least != null && least.isPositive()) {
                share = share.max(least);
            }
            if (most != null && most.isPositive()) {
                share = share.min(most);
            }
        }
        return rung.max(share);
    }

    /** Whether somebody may fine this much; {@code max} zero is no limit, and the unlimited are never held to it. */
    public Verdict mayFine(boolean unlimited, Money amount, Money max, String maxDescribed) {
        if (unlimited || max == null || !max.isPositive() || amount == null || !amount.isMoreThan(max)) {
            return Verdict.allowed();
        }
        return Verdict.refused(TOO_MUCH, maxDescribed);
    }

    /** The victim's share of a fine, rounded down; the rest is the server's. */
    public Split split(Money amount, int victimPercent) {
        if (amount == null || !amount.isPositive() || victimPercent <= 0) {
            return new Split(Money.ZERO, amount == null ? Money.ZERO : amount);
        }
        Money victim = amount.share(Math.min(victimPercent, 100) / 100.0);
        return new Split(victim, amount.minus(victim));
    }

    @Override
    public String describe() {
        return "what a fine comes to: a warning's rung or share of the balance, a moderator's limit, the victim's share";
    }
}
