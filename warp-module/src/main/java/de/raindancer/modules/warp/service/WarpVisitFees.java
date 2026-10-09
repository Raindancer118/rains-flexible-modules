package de.raindancer.modules.warp.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.rules.WarpFeeRule;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What visiting somebody's warp costs: taken from the visitor, paid to the owner, minus the server's cut.
 *
 * <p>Taken when the trip starts, because Core's {@code Travel} has no hook between the wait and the
 * teleport, and handed back by {@link #refund} when the trip does not happen. The owner's share is a
 * player-to-player transfer, and the cut is charged to the visitor as a sink ({@link #CUT_SOURCE}) so
 * the economy sees it leave.
 */
public final class WarpVisitFees implements IWarpService {

    /** The economy source of a fee between players. */
    public static final String SOURCE = "warp.visit";
    /** The economy source of the part of a fee the server keeps. */
    public static final String CUT_SOURCE = "warp.visit-cut";

    private static final LogChannel log = Log.of("warps");

    /** What one visitor has paid for a trip not yet finished. */
    private record Paid(UUID owner, Money toOwner, Money cut, String warp) {
    }

    private final Messages messages;
    private final WarpFeeRule rule;
    private final Map<UUID, Paid> pending = new HashMap<>();
    private volatile WarpSettings settings;

    public WarpVisitFees(Messages messages, WarpFeeRule rule, WarpSettings settings) {
        this.messages = messages;
        this.rule = rule;
        this.settings = settings;
    }

    @Override
    public void settings(WarpSettings fresh) {
        this.settings = fresh;
    }

    /** What the owners may charge at most. */
    public Money cap() {
        return Fees.amount(settings.mostVisitFee());
    }

    /** What a visit to this warp costs a stranger now: its fee, read against the cap. */
    public Money feeOf(Warp warp) {
        return rule.visitFee(warp.visitFee(), cap());
    }

    /** What visiting this warp would cost this player: zero for its owner, for staff and when it is free. */
    public Money costFor(Player visitor, Warp warp) {
        Money fee = feeOf(warp);
        return rule.pays(visitor.getUniqueId(), warp.owner().orElse(null), rule.bypasses(visitor::hasPermission))
                ? fee : Money.ZERO;
    }

    /**
     * Takes the fee for visiting this warp.
     *
     * @return whether the trip may go on; a refusal has been said and nothing has moved
     */
    public boolean charge(Player visitor, Warp warp) {
        Money fee = feeOf(warp);
        UUID who = visitor.getUniqueId();
        UUID owner = warp.owner().orElse(null);
        if (!fee.isPositive() || !rule.pays(who, owner, rule.bypasses(visitor::hasPermission))) {
            return true;
        }
        Economy bank = Economies.current().orElse(null);
        if (bank == null) {
            messages.send(visitor, "warps.visit.no-economy");
            return false;
        }
        Money cut = rule.cut(fee, settings.visitCutPercent());
        Money toOwner = fee.minus(cut);

        EconomyResult cutTaken = Fees.charge(who, cut, "Cut of a visit to warp " + warp.name(), CUT_SOURCE);
        if (!cutTaken.succeeded()) {
            refuse(visitor, fee, cutTaken);
            return false;
        }
        if (toOwner.isPositive()) {
            EconomyResult paid = bank.transfer(who, owner, toOwner, "Visit to warp " + warp.name());
            if (!paid.succeeded()) {
                Fees.refund(who, cutTaken.amount(), "Visit to warp " + warp.name() + " not paid", CUT_SOURCE);
                refuse(visitor, fee, paid);
                return false;
            }
        }
        synchronized (pending) {
            pending.put(who, new Paid(owner, toOwner, cutTaken.amount(), warp.name()));
        }
        messages.send(visitor, "warps.visit.charged", "name", warp.label(), "price", Fees.format(fee));
        return true;
    }

    private void refuse(Player visitor, Money fee, EconomyResult result) {
        switch (result.outcome()) {
            case NOT_ENOUGH -> messages.send(visitor, "warps.visit.cannot-afford", "price", Fees.format(fee));
            case UNAVAILABLE -> messages.send(visitor, "warps.visit.no-economy");
            default -> messages.send(visitor, "warps.visit.refused");
        }
    }

    /** The trip finished: the money stays where it went. */
    public void settle(UUID visitor) {
        synchronized (pending) {
            pending.remove(visitor);
        }
    }

    /**
     * The trip did not happen: gives back what was taken.
     *
     * <p>The owner's share comes back from the owner, which fails if they have already spent it; the
     * visitor is then not made whole from nowhere (that would let two accounts print money by cancelling
     * trips), and it is logged. The server's cut goes back in full.
     *
     * @return what the visitor got back, zero when there was nothing to give back
     */
    public Money refund(UUID visitor) {
        Paid paid;
        synchronized (pending) {
            paid = pending.remove(visitor);
        }
        if (paid == null) {
            return Money.ZERO;
        }
        Money back = Money.ZERO;
        Economy bank = Economies.current().orElse(null);
        if (bank != null && paid.toOwner().isPositive()) {
            EconomyResult returned = bank.transfer(paid.owner(), visitor, paid.toOwner(),
                    "Visit to warp " + paid.warp() + " did not happen");
            if (returned.succeeded()) {
                back = back.plus(paid.toOwner());
            } else {
                log.warn("Could not take {} back from the owner of warp {} for {}: {}.",
                        Fees.format(paid.toOwner()), paid.warp(), visitor, returned.outcome());
            }
        }
        if (paid.cut().isPositive()) {
            EconomyResult returned = Fees.refund(visitor, paid.cut(),
                    "Visit to warp " + paid.warp() + " did not happen", CUT_SOURCE);
            if (returned.succeeded()) {
                back = back.plus(paid.cut());
            }
        }
        return back;
    }

    /** Hands back everything still waiting on a trip, for a module being stopped. */
    public void refundAll() {
        List<UUID> owing;
        synchronized (pending) {
            owing = new ArrayList<>(pending.keySet());
        }
        owing.forEach(this::refund);
    }

    @Override
    public String describe() {
        return "what visiting somebody's warp costs";
    }
}
