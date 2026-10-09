package de.raindancer.modules.tpa.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.rules.TpaPriceRule;
import org.bukkit.Location;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What teleports cost, taking it and giving it back.
 *
 * <p>Three sources, so an economy's levers can treat them apart: {@link #TRIP} for a request, {@link #BACK}
 * for going back, {@link #SKIP} for paying out of a wait. What is actually taken is kept, because the
 * price index makes that differ from what the file says and a refund gives back what was taken.
 *
 * <p>Money taken for a trip that is still counting down is <em>held</em> per traveller. Travel gives no
 * callback when somebody quits mid-wait or the module stops, so {@link #refundHeld} and
 * {@link #refundAllHeld} are what keep a trip nobody made from costing anything.
 */
public final class TpaFees implements ITpaService {

    public static final String TRIP = "tpa.fee";
    public static final String BACK = "tpa.back";
    public static final String SKIP = "tpa.skip-cooldown";

    /** What was taken: the main price under its source, and the bought skip, if any. */
    public record Taken(Money main, String source, Money skip) {
        public static final Taken NOTHING = new Taken(Money.ZERO, TRIP, Money.ZERO);

        public Money total() {
            return main.plus(skip);
        }
    }

    /** Either what was taken, or the refusal that stopped it — with nothing left taken. */
    public record Charge(Taken taken, EconomyResult refusal) {
        public boolean paid() {
            return refusal == null;
        }
    }

    private final TpaPriceRule rule = new TpaPriceRule();
    private final Map<UUID, Taken> held = Collections.synchronizedMap(new HashMap<>());
    private volatile TpaSettings settings;

    public TpaFees(TpaSettings settings) {
        settings(settings);
    }

    @Override
    public void settings(TpaSettings fresh) {
        this.settings = fresh == null ? TpaSettings.DEFAULTS : fresh;
    }

    /** The price of a trip between two places, as written — before the economy's price level. */
    public Money trip(Location from, Location to) {
        boolean same = from != null && to != null && from.getWorld() != null
                && from.getWorld().equals(to.getWorld());
        return trip(same, same ? from.distance(to) : Double.NaN);
    }

    public Money trip(boolean sameWorld, double blocks) {
        TpaSettings now = settings;
        return rule.trip(Fees.amount(now.price()), Fees.amount(now.pricePer100Blocks()),
                Fees.amount(now.crossWorldPrice()), sameWorld, blocks);
    }

    public Money back() {
        return Fees.amount(settings.backPrice());
    }

    public Money skip() {
        return Fees.amount(settings.skipCooldownPrice());
    }

    /** Takes {@code main} under {@code source} and {@code skip} under {@link #SKIP}, or neither. */
    public Charge charge(UUID who, Money main, String source, Money skip) {
        EconomyResult first = Fees.charge(who, main, "Teleport", source);
        if (!first.succeeded()) {
            return new Charge(Taken.NOTHING, first);
        }
        EconomyResult second = Fees.charge(who, skip, "Skipping a wait for a teleport", SKIP);
        if (!second.succeeded()) {
            Fees.refund(who, first.amount(), "Teleport not made", source);
            return new Charge(Taken.NOTHING, second);
        }
        return new Charge(new Taken(first.amount(), source, second.amount()), null);
    }

    public void refund(UUID who, Taken taken) {
        if (taken == null) {
            return;
        }
        Fees.refund(who, taken.main(), "Teleport not made", taken.source());
        Fees.refund(who, taken.skip(), "Teleport not made", SKIP);
    }

    /** Remembers what a trip still counting down was paid with. */
    public void hold(UUID who, Taken taken) {
        if (who != null && taken != null && taken.total().isPositive()) {
            held.put(who, taken);
        }
    }

    /** The trip ended — what was held is spent, or about to be given back by the caller. */
    public Taken settle(UUID who) {
        return who == null ? null : held.remove(who);
    }

    /** They left before the trip ended: it never happened, so nothing is kept. */
    public void refundHeld(UUID who) {
        refund(who, settle(who));
    }

    /** The module is stopping: every trip still counting down is called off. */
    public void refundAllHeld() {
        UUID[] everyone;
        synchronized (held) {
            everyone = held.keySet().toArray(new UUID[0]);
        }
        for (UUID who : everyone) {
            refundHeld(who);
        }
    }

    @Override
    public String describe() {
        return "taking and giving back what teleports cost";
    }
}
