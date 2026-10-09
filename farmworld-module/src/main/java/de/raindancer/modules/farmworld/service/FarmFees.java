package de.raindancer.modules.farmworld.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.farmworld.FarmWorldSettings;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What entering a farm world costs, taking it and giving it back.
 *
 * <p>Two sources, {@link #ENTRY} and {@link #PASS}, so an economy's levers can treat them apart. What was
 * actually taken is kept, because the price index makes that differ from what the file says and a refund
 * gives back what was taken.
 *
 * <p>Money taken for a trip still counting down is <em>held</em> per traveller. Travel gives no callback
 * when somebody quits mid-wait or the module stops, so {@link #refundHeld} and {@link #refundAllHeld} are
 * what keep a trip nobody made from costing anything.
 */
public final class FarmFees implements IFarmWorldService {

    public static final String ENTRY = "farmworld.entry";
    public static final String PASS = "farmworld.pass";

    /** What was taken and under which source — the source says whether it bought a pass. */
    public record Taken(Money amount, String source) {
        public static final Taken NOTHING = new Taken(Money.ZERO, ENTRY);

        public boolean isPass() {
            return PASS.equals(source);
        }
    }

    /** Either what was taken, or the refusal that stopped it — with nothing taken. */
    public record Charge(Taken taken, EconomyResult refusal) {
        public boolean paid() {
            return refusal == null;
        }
    }

    private final Map<UUID, Taken> held = Collections.synchronizedMap(new HashMap<>());
    private volatile FarmWorldSettings settings;

    public FarmFees(FarmWorldSettings settings) {
        settings(settings);
    }

    @Override
    public void settings(FarmWorldSettings fresh) {
        this.settings = fresh == null ? FarmWorldSettings.DEFAULTS : fresh;
    }

    /** The price of one entry, as written — before the economy's price level. */
    public Money entry() {
        return Fees.amount(settings.entryPrice());
    }

    /** The price of a day pass, as written. */
    public Money pass() {
        return Fees.amount(settings.dayPassPrice());
    }

    public Charge charge(UUID who, Money amount, String source) {
        String reason = PASS.equals(source) ? "Farm world day pass" : "Farm world entry";
        EconomyResult result = Fees.charge(who, amount, reason, source);
        return result.succeeded()
                ? new Charge(new Taken(result.amount(), source), null)
                : new Charge(Taken.NOTHING, result);
    }

    public void refund(UUID who, Taken taken) {
        if (taken != null) {
            Fees.refund(who, taken.amount(), "Farm world trip not made", taken.source());
        }
    }

    /** Remembers what a trip still counting down was paid with. */
    public void hold(UUID who, Taken taken) {
        if (who != null && taken != null && taken.amount().isPositive()) {
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
        return "taking and giving back what entering a farm world costs";
    }
}
