package de.raindancer.modules.homes.rules;

import de.raindancer.core.platform.permission.NumberedLimit;
import de.raindancer.modules.homes.util.PermissionNodes;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * How many homes somebody may have.
 *
 * <h2>Where the hard part went</h2>
 * Reading {@code homes.limit.<n>} correctly is Core's {@code NumberedLimit}, and it is correct for a
 * reason worth knowing: asking {@code hasPermission("homes.limit." + n)} per number is wrong on
 * Bukkit, because an <em>undeclared</em> node defaults to true for an operator. Every operator held
 * {@code homes.limit.100} and was quietly given a hundred homes on a server configured for three.
 *
 * <p>What is left here is the module's own two answers on top: the unlimited node, and whether being
 * an operator counts as holding it — which the owner decides, and which defaults to no.
 */
public final class HomeLimitRule implements IHomeRule {

    /**
     * Whether this player has no limit.
     *
     * @param granted        what they have actually been granted
     * @param isOperator     whether they are one
     * @param operatorsBypass whether the owner said an operator counts as holding the node
     */
    public boolean isUnlimited(Set<String> granted, boolean isOperator, boolean operatorsBypass) {
        return (granted != null && granted.contains(PermissionNodes.UNLIMITED))
                || (isOperator && operatorsBypass);
    }

    /** How many they may have, given what the config says. */
    public int limitFor(Set<String> granted, boolean isOperator, boolean operatorsBypass,
                        int configured) {
        if (isUnlimited(granted, isOperator, operatorsBypass)) {
            return Integer.MAX_VALUE;
        }
        return NumberedLimit.reading(PermissionNodes.LIMIT_PREFIX, granted).highestOf(configured);
    }

    /** Whether there is room for one more. */
    public boolean isRoomFor(int howManyTheyHave, Set<String> granted, boolean isOperator,
                             boolean operatorsBypass, int configured) {
        return howManyTheyHave < limitFor(granted, isOperator, operatorsBypass, configured);
    }

    /**
     * Whether they may move a home they already have, at the limit.
     *
     * <p>Always yes, and that is the whole method. Without it, a server that lowers the number traps
     * everybody who is now over it: they cannot move a home, and moving is how a badly placed one is
     * fixed, so the only thing left is to delete. Reachable in one step by an owner editing a config.
     */
    public boolean mayReplace(int howManyTheyHave, Set<String> granted, boolean isOperator,
                              boolean operatorsBypass, int configured) {
        return true;
    }

    /**
     * The limit as a player should read it.
     *
     * <p>{@code ∞} rather than {@link Integer#MAX_VALUE}: "2147483647" on somebody's screen is a bug
     * they will report.
     */
    public String describeLimit(Set<String> granted, boolean isOperator, boolean operatorsBypass,
                                int configured) {
        if (isUnlimited(granted, isOperator, operatorsBypass)) {
            return NumberedLimit.NO_LIMIT;
        }
        return String.valueOf(limitFor(granted, isOperator, operatorsBypass, configured));
    }

    /**
     * What a player has been granted, read off a live one.
     *
     * <p>The one method here that needs a server, kept apart from every decision above so those can
     * be asked a hundred ways in a test. {@code getEffectivePermissions} rather than
     * {@code hasPermission} — see the class note.
     */
    public static Set<String> grantsOf(Player player) {
        if (player == null) {
            return Set.of();
        }
        Set<String> granted = new java.util.LinkedHashSet<>();
        player.getEffectivePermissions().stream()
                .filter(held -> held.getValue())
                .forEach(held -> granted.add(held.getPermission()));
        // Asked directly as well: it is declared, so hasPermission is safe for it, and it may come
        // from a group whose grants are not listed one by one.
        if (player.hasPermission(PermissionNodes.UNLIMITED)) {
            granted.add(PermissionNodes.UNLIMITED);
        }
        return granted;
    }

    @Override
    public String describe() {
        return "how many homes somebody may have";
    }
}
