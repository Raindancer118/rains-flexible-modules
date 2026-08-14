package de.raindancer.modules.mannequin.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.rules.SignalStrengthRule;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * A heavy hit's redstone comparator pulse.
 *
 * <h2>Design choice: a pulse, not a sustained state</h2>
 * The barrel is filled to the level a comparator would read for the hit's signal strength, then
 * cleared a short time later. A sustained model — leave it at the last hit's level until the next
 * hit changes it — was the alternative, and it is not chosen because it goes stale in a misleading
 * direction: a heavy hit followed by several light ones would leave the barrel reporting the heavy
 * hit's signal indefinitely, and anyone watching the comparator later has no way to tell whether
 * that reading is live or three minutes old. A pulse is unambiguous — a redstone event fires exactly
 * when a heavy hit did, and nothing else.
 *
 * <h2>The formula</h2>
 * Vanilla computes a container's comparator output as
 * {@code floor(1 + (itemsInContainer / maxStackableItems) * 14)} once it holds anything at all, and
 * {@code 0} when it holds nothing. A barrel has 27 slots, so {@code maxStackableItems = 27 * 64}.
 * {@link #itemsForSignal} inverts that to find how many filler items produce a given level.
 */
public final class MannequinRedstoneService implements IMannequinService {

    private static final LogChannel log = Log.of("mannequin");

    /** Any stackable, non-meaningful material — nothing a player would read as "an item". */
    private static final Material FILLER = Material.STONE;

    /**
     * A comparator reads a container from directly beside it, always at the same height as the
     * comparator itself — never from above or below.
     */
    private static final BlockFace[] COMPARATOR_SIDES =
            {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /**
     * Vanilla's own constant in {@code floor(1 + (items / max) * 14)} — the 15 possible non-zero
     * signals (1 through 15) are 14 equal steps apart, not 15; using {@link SignalStrengthRule#MAX_SIGNAL}
     * here instead would be one step too many and undershoot a full container's signal.
     */
    private static final int VANILLA_STEPS = 14;

    private final Plugin plugin;
    private volatile MannequinSettings settings;

    public MannequinRedstoneService(Plugin plugin, MannequinSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    /** How many filler items a container with this many slots needs to report {@code desiredSignal}. */
    public static int itemsForSignal(int desiredSignal, int slots) {
        int level = Math.max(0, Math.min(SignalStrengthRule.MAX_SIGNAL, desiredSignal));
        if (level <= 0 || slots <= 0) {
            return 0;
        }
        int max = slots * 64;
        int items = (int) Math.max(1, Math.ceil((level - 1) * max / (double) VANILLA_STEPS));
        return Math.min(max, items);
    }

    /** The inverse: what a comparator reads for this many items in a container this size. */
    public static int signalForItems(int items, int slots) {
        if (items <= 0 || slots <= 0) {
            return 0;
        }
        int max = slots * 64;
        int clamped = Math.min(max, items);
        int signal = (int) Math.floor(1 + (clamped / (double) max) * VANILLA_STEPS);
        return Math.max(0, Math.min(SignalStrengthRule.MAX_SIGNAL, signal));
    }

    /**
     * Fills the barrel to read {@code desiredSignal}, then clears it {@code clearAfterTicks} later.
     *
     * <p>Does nothing when the block is not actually a barrel any more — an owner may have broken
     * it, and a hit landing after that is not a reason to place one back.
     */
    public void pulse(Block barrel, int desiredSignal, long clearAfterTicks) {
        // getState(false): the real block entity, not getState()'s detached snapshot. A snapshot's
        // inventory is a private copy — filling it and writing it back with update() changes what
        // the barrel contains, but skips whatever internal step a vanilla container mutation (a
        // player's own click, a hopper, a /item command) uses to tell an adjacent comparator to
        // recompute. That gap was the entire reason a hit's pulse produced the right item count in
        // its own debug log line and still left every comparator reading 0: confirmed by hand before
        // this fix by filling the same barrel through a plain vanilla command instead of this
        // service, which worked immediately. Operating on the live entity directly is what a normal
        // container interaction does, so it carries that notification the same way.
        if (barrel == null || !(barrel.getState(false) instanceof Barrel state)) {
            log.debug("[redstone] pulse skipped: block at {} is not a barrel (was it broken?).",
                    barrel == null ? "null" : barrel.getLocation());
            return;
        }
        Inventory inventory = state.getInventory();
        inventory.clear();
        int items = itemsForSignal(desiredSignal, inventory.getSize());
        fill(inventory, items);
        refreshAdjacentComparators(barrel);
        log.debug("[redstone] pulse at {}: signal {} -> {} item(s), for {} tick(s).",
                barrel.getLocation(), desiredSignal, items, clearAfterTicks);

        if (plugin == null) {
            return;
        }
        Scheduling.regionTimer(plugin, barrel.getLocation(), Math.max(1L, clearAfterTicks),
                Math.max(1L, clearAfterTicks), task -> {
                    try {
                        if (barrel.getState(false) instanceof Barrel stillBarrel) {
                            stillBarrel.getInventory().clear();
                            refreshAdjacentComparators(barrel);
                        }
                    } finally {
                        task.cancel();
                    }
                });
    }

    private static void fill(Inventory inventory, int items) {
        int remaining = items;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            int amount = Math.min(64, remaining);
            inventory.setItem(slot, new ItemStack(FILLER, amount));
            remaining -= amount;
        }
    }

    /**
     * Forces every comparator directly beside the barrel to re-check it, right now — defence in
     * depth alongside {@code state.update()}'s own physics update, not a replacement for it.
     *
     * <h2>A correction, left here so the mistake is not repeated</h2>
     * An earlier version of this class cited
     * <a href="https://github.com/PaperMC/Paper/issues/505">PaperMC/Paper#505</a> as a "confirmed,
     * still open" Paper bug that made this call necessary. That was wrong on the one fact that
     * actually mattered: the issue was closed as fixed in 2016, a decade before this module existed
     * — whatever was stopping a comparator from noticing an API-driven container change on old
     * Paper builds should already be gone on 26.2, and {@code state.update()}'s own "trigger a
     * physics update to surrounding blocks" ought to be enough on its own. This call stays anyway,
     * because it costs nothing and directly targets the one block that actually matters if some
     * other, unrelated reason keeps a comparator from rechecking — but if the redstone signal is
     * still not appearing after this, <strong>the actual fault is almost certainly not this
     * method</strong>. Read {@link #pulse}'s debug log line first: it says the exact block the
     * barrel sits at and how many items were placed. If that line shows a sane item count at the
     * expected coordinates and a comparator still reads nothing, the comparator is very likely not
     * actually touching that barrel — it has to sit at <em>the same Y level as the barrel itself</em>,
     * on one of its four horizontal sides, facing toward it; one level above (at the mannequin's own
     * feet height) reads nothing at all, which is an easy mistake to make since {@link
     * de.raindancer.modules.mannequin.service.MannequinService}'s barrel sits one full block
     * <em>under</em> where the mannequin stands.
     */
    static void refreshAdjacentComparators(Block barrel) {
        for (BlockFace side : COMPARATOR_SIDES) {
            Block neighbor = barrel.getRelative(side);
            if (neighbor.getType() == Material.COMPARATOR) {
                neighbor.getState().update(true, true);
            }
        }
    }
}
