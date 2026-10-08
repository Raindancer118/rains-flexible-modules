package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.PaymentRefusal;

import java.util.Optional;
import java.util.UUID;

/** Whether a payment may start, what tax it pays, and whether it needs a second click. */
public final class PaymentRule implements IEconomyRule {

    /** The most tax can ever take, whatever a setting says: a payment that arrives as nothing is a bug report. */
    private static final double MOST_TAX = 0.5;

    public Optional<PaymentRefusal> refusal(UUID from, UUID to, Money amount, Money minimum) {
        if (from.equals(to)) {
            return Optional.of(PaymentRefusal.TO_YOURSELF);
        }
        if (!amount.isPositive()) {
            return Optional.of(PaymentRefusal.NOT_POSITIVE);
        }
        if (minimum.isPositive() && !amount.isAtLeast(minimum)) {
            return Optional.of(PaymentRefusal.BELOW_MINIMUM);
        }
        return Optional.empty();
    }

    /** Taken from what arrives, rounded down. */
    public Money tax(Money amount, double fraction) {
        return amount.share(Math.min(MOST_TAX, Math.max(0, fraction)));
    }

    /** A threshold of zero means never ask. */
    public boolean needsConfirming(Money amount, Money threshold) {
        return threshold.isPositive() && amount.isMoreThan(threshold);
    }

    @Override
    public String describe() {
        return "whether a payment may start, the tax it pays, and whether it needs confirming";
    }
}
