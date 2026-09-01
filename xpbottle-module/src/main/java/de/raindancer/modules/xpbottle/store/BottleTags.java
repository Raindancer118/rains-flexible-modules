package de.raindancer.modules.xpbottle.store;

import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * What is written on a bottle, and how it is read back.
 *
 * <h2>Why the persistent data container and nothing else</h2>
 * Not the display name, and not the lore. Both of those are things an anvil and a book can forge,
 * and a forged bottle is experience out of nothing — the single worst bug this module could have.
 * The container is not editable in survival, so the number in it is the number that went in.
 *
 * <h2>Why the namespace is a literal and not the host plugin</h2>
 * {@code new NamespacedKey(plugin, …)} takes its namespace from whichever plugin happens to be
 * hosting the module, so the same bottle read under {@code RainsXPBottles} and under
 * {@code RainsSMPCore} would be two different keys — and a server that moved the module from one to
 * the other would find every bottle already in a chest reading as an ordinary glass bottle, its
 * contents gone. A fixed namespace is the same key wherever the module lives.
 *
 * <h2>What counts as a bottle at all</h2>
 * Three things, and nothing else: a stack carrying this module's tier tag; a plain glass bottle,
 * which is the empty state of the plain path and carries no tag because it has nothing to say yet;
 * and nothing more. An untagged vanilla bottle o' enchanting is deliberately not one of them —
 * intercepting those would break throwing them, which is a thing players do.
 */
public final class BottleTags {

    /** The tier: 0 for a plain bottle, 1 and up for a siphon. Its presence is what marks a stack. */
    public static final NamespacedKey TIER =
            Objects.requireNonNull(NamespacedKey.fromString("rainsxpbottles:tier"));

    /** Experience points in it, exactly. */
    public static final NamespacedKey STORED =
            Objects.requireNonNull(NamespacedKey.fromString("rainsxpbottles:stored"));

    private BottleTags() {
    }

    /** Whether this stack is one of ours — i.e. carries the tier tag. */
    public static boolean isTagged(ItemStack stack) {
        return container(stack).map(data -> data.has(TIER, PersistentDataType.INTEGER))
                .orElse(false);
    }

    /**
     * The bottle this stack is, if it is one.
     *
     * @param settings what capacities are in force right now — a bottle read yesterday under a
     *                 larger capacity is still read, and simply has no room
     */
    public static Optional<Bottle> read(ItemStack stack, XpBottleSettings settings) {
        if (stack == null || stack.getType() == Material.AIR || settings == null) {
            return Optional.empty();
        }
        Optional<PersistentDataContainer> data = container(stack);
        if (data.isPresent() && data.get().has(TIER, PersistentDataType.INTEGER)) {
            int level = Optional.ofNullable(data.get().get(TIER, PersistentDataType.INTEGER))
                    .orElse(0);
            int stored = Optional.ofNullable(data.get().get(STORED, PersistentDataType.INTEGER))
                    .orElse(0);
            return Optional.of(new Bottle(level, stored, settings.capacityFor(level)));
        }
        if (stack.getType() == Material.GLASS_BOTTLE) {
            // The empty state of the plain path. Untagged on purpose: an ordinary glass bottle is
            // still an ordinary glass bottle until somebody puts something in it, and tagging every
            // one a player crafts would make water bottles behave oddly for no gain.
            return Optional.of(Bottle.empty(settings.plainCapacity()));
        }
        return Optional.empty();
    }

    /**
     * Writes a bottle's tier and contents onto a stack.
     *
     * <p>The capacity is deliberately not written: it belongs to the settings, and a copy on the
     * item would be a second source of truth that goes stale the moment an owner changes one.
     *
     * @return the same stack, for chaining
     */
    public static ItemStack stamp(ItemStack stack, Bottle bottle) {
        if (stack == null || bottle == null) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(TIER, PersistentDataType.INTEGER, bottle.level());
        meta.getPersistentDataContainer().set(STORED, PersistentDataType.INTEGER, bottle.stored());
        stack.setItemMeta(meta);
        return stack;
    }

    private static Optional<PersistentDataContainer> container(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? Optional.empty() : Optional.of(meta.getPersistentDataContainer());
    }
}
