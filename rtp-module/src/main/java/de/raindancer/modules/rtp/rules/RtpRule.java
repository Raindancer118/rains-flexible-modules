package de.raindancer.modules.rtp.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.world.protection.FlagPolicy;

import java.util.List;

/**
 * Whether a random teleport may even be attempted here.
 *
 * <h2>What is deliberately not decided here</h2>
 * The cooldown and the permission. Both need something this rule is not handed — the cooldown needs a
 * clock and a player to remember against, which is exactly what {@code Cooldowns} already is; the
 * permission needs a {@code CommandSender}. Asking either through a rule would mean constructing a fake
 * one just to satisfy the signature, for no gain over asking the real thing directly. What is left, and
 * genuinely worth a rule, is the one question that is pure: is this world one a random teleport is
 * even allowed to happen in.
 */
public final class RtpRule implements IRtpRule {

    public static final String NO_WORLD = "rtp.no-world";
    public static final String WORLD_DISABLED = "rtp.world-disabled";

    /** Whether a trip may be attempted in this world. */
    public Verdict mayGo(String world, List<String> disabledWorlds) {
        if (world == null || world.isBlank()) {
            return Verdict.refused(NO_WORLD);
        }
        List<String> disabled = disabledWorlds == null ? List.of() : disabledWorlds;
        for (String name : disabled) {
            if (name != null && name.equalsIgnoreCase(world)) {
                return Verdict.refused(WORLD_DISABLED, world);
            }
        }
        return Verdict.allowed();
    }

    /**
     * Whether this particular trip should have its landing checked for safety, once the owner's
     * policy and the player's own request have both had their say.
     *
     * <p>Reused rather than reinvented: {@link FlagPolicy} is Core's own "who decides" enum, already
     * shaped exactly right — {@code AVAILABLE} for the player's own choice, {@code FORCED_ON} and
     * {@code FORCED_OFF} for an owner who wants it settled one way regardless, and
     * {@code DISABLED} — treated the same as {@code FORCED_OFF} here — for an owner who does not
     * even want the question asked.
     *
     * @param playerWantsSafe what the player asked for this trip, ignored under any policy but
     *                        {@code AVAILABLE}
     */
    public boolean effectiveSafeArrival(FlagPolicy policy, boolean playerWantsSafe) {
        FlagPolicy resolved = policy == null ? FlagPolicy.AVAILABLE : policy;
        return switch (resolved) {
            case FORCED_ON -> true;
            case FORCED_OFF, DISABLED -> false;
            case AVAILABLE -> playerWantsSafe;
        };
    }

    @Override
    public String describe() {
        return "whether a random teleport is allowed to happen in this world at all, and whether its "
                + "landing is checked for safety";
    }
}
