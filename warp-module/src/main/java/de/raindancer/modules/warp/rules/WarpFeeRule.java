package de.raindancer.modules.warp.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.warp.util.PermissionNodes;

import java.util.UUID;
import java.util.function.Predicate;

/** What a visit costs and who is spared it. Pure arithmetic: nothing is charged here. */
public final class WarpFeeRule implements IWarpRule {

    /**
     * What a visit costs, given what the owner wrote and what the server allows.
     *
     * <p>Read as the lower of the two each time, so lowering the cap takes effect on every warp at once
     * without anybody rewriting them. A cap of zero means owners may not charge.
     */
    public Money visitFee(Money stored, Money cap) {
        if (stored == null || cap == null || !stored.isPositive() || !cap.isPositive()) {
            return Money.ZERO;
        }
        return stored.min(cap);
    }

    /** The server's share of a fee, rounded down; a percentage outside 0 to 100 is clamped. */
    public Money cut(Money fee, int percent) {
        if (fee == null || !fee.isPositive()) {
            return Money.ZERO;
        }
        int clamped = Math.max(0, Math.min(100, percent));
        // Whole numbers all the way: a double would be a cent short on a percentage like 29.
        return Money.of(java.math.BigInteger.valueOf(fee.minor()).multiply(java.math.BigInteger.valueOf(clamped))
                .divide(java.math.BigInteger.valueOf(100)).longValueExact());
    }

    /** Whether this visitor pays: not the owner, not anybody exempt, and not for a warp with nobody to pay. */
    public boolean pays(UUID visitor, UUID owner, boolean bypasses) {
        return owner != null && !bypasses && !owner.equals(visitor);
    }

    /** Whether somebody skips every warp fee: staff who manage warps, or the bypass node. */
    public boolean bypasses(Predicate<String> hasPermission) {
        return hasPermission != null && (hasPermission.test(PermissionNodes.MANAGE)
                || hasPermission.test(PermissionNodes.BYPASS_FEES));
    }

    @Override
    public String describe() {
        return "what a visit costs and who is spared it";
    }
}
