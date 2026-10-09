package de.raindancer.modules.roles.rules;

import java.time.Duration;

/**
 * How strong a role's perks are by now: they start at a share of their full size and grow evenly to it,
 * so a role is worth keeping, and swapping roles to chase a discount starts each one small again.
 */
public final class TenureRule implements IRolesRule {

    /**
     * @param startPercent  the share a brand-new role starts at, 0–100
     * @param fullAfterDays days until the perks are full; zero means full at once
     * @return 0 to 1
     */
    public double strength(long heldSince, long now, int startPercent, int fullAfterDays) {
        double start = Math.clamp(startPercent, 0, 100) / 100.0;
        if (fullAfterDays <= 0) {
            return 1.0;
        }
        long held = Math.max(0, now - heldSince);
        double grown = Math.min(1.0, held / (double) Duration.ofDays(fullAfterDays).toMillis());
        return Math.min(1.0, start + (1.0 - start) * grown);
    }

    /** A perk at this strength: rounded, and at least one percent while it does anything at all. */
    public int scaled(int perkPercent, double strength) {
        if (perkPercent == 0 || strength <= 0) {
            return 0;
        }
        int size = (int) Math.round(Math.abs(perkPercent) * Math.min(1.0, strength));
        return Integer.signum(perkPercent) * Math.clamp(size, 1, Math.abs(perkPercent));
    }

    public Duration untilFull(long heldSince, long now, int fullAfterDays) {
        long left = heldSince + Duration.ofDays(Math.max(0, fullAfterDays)).toMillis() - now;
        return left <= 0 ? Duration.ZERO : Duration.ofMillis(left);
    }

    @Override
    public String describe() {
        return "how strong a role's perks are, growing with how long the role has been held";
    }
}
