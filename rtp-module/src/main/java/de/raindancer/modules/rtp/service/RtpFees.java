package de.raindancer.modules.rtp.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.rtp.RtpSettings;

import java.util.UUID;

/**
 * Taking and giving back what a random teleport costs.
 *
 * <p>Two sources, so an economy's levers can treat the trip and the bought skip differently. What was
 * actually taken is kept, because the price index and levers make that differ from what the file says,
 * and a refund must give back that, not the written amount.
 */
public final class RtpFees implements IRtpService {

    public static final String FEE = "rtp.fee";
    public static final String SKIP = "rtp.skip-cooldown";

    private volatile RtpSettings settings;

    public RtpFees(RtpSettings settings) {
        settings(settings);
    }

    @Override
    public void settings(RtpSettings fresh) {
        this.settings = fresh == null ? RtpSettings.DEFAULTS : fresh;
    }

    /** What a trip costs as the owner wrote it, before the economy's price level. */
    public Money fee() {
        return Fees.amount(settings.price());
    }

    /** What skipping the wait costs as the owner wrote it. */
    public Money skip() {
        return Fees.amount(settings.skipCooldownPrice());
    }

    /** What was taken: the fee and, separately, the bought skip. */
    public record Taken(Money fee, Money skip) {
        public static final Taken NOTHING = new Taken(Money.ZERO, Money.ZERO);

        public Money total() {
            return fee.plus(skip);
        }
    }

    /** Either what was taken, or the refusal that stopped it — with nothing left taken. */
    public record Charge(Taken taken, EconomyResult refusal) {
        public boolean paid() {
            return refusal == null;
        }
    }

    public Charge charge(UUID who, Money fee, Money skip) {
        EconomyResult first = Fees.charge(who, fee, "Random teleport", FEE);
        if (!first.succeeded()) {
            return new Charge(Taken.NOTHING, first);
        }
        EconomyResult second = Fees.charge(who, skip, "Skipping the wait for a random teleport", SKIP);
        if (!second.succeeded()) {
            Fees.refund(who, first.amount(), "Random teleport not made", FEE);
            return new Charge(Taken.NOTHING, second);
        }
        return new Charge(new Taken(first.amount(), second.amount()), null);
    }

    public void refund(UUID who, Taken taken) {
        if (taken == null) {
            return;
        }
        Fees.refund(who, taken.fee(), "Random teleport not made", FEE);
        Fees.refund(who, taken.skip(), "Random teleport not made", SKIP);
    }
}
