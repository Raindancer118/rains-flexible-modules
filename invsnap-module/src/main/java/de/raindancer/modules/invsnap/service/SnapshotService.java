package de.raindancer.modules.invsnap.service;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.model.Snapshot;
import de.raindancer.modules.invsnap.model.TrackedPlayer;
import de.raindancer.modules.invsnap.rules.RetentionRule;
import de.raindancer.modules.invsnap.store.SnapshotStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Takes a snapshot of a live inventory, and puts one back.
 *
 * <p>The one class in this module that touches a real {@link PlayerInventory} — everything either
 * side of it (what a snapshot <em>is</em>, which ones survive, where they are kept) is plain data
 * or a pure rule, so only this one class needs a running server to exercise directly.
 */
public final class SnapshotService implements IInvSnapService {

    private final LogChannel log;
    private final SnapshotStore store;
    private final RetentionRule retentionRule;
    private volatile InvSnapSettings settings;

    public SnapshotService(LogChannel log, SnapshotStore store, RetentionRule retentionRule,
                           InvSnapSettings settings) {
        this.log = log;
        this.store = store;
        this.retentionRule = retentionRule;
        this.settings = settings;
    }

    @Override
    public void settings(InvSnapSettings settings) {
        this.settings = settings;
    }

    /** Takes a snapshot of this player right now, prunes to the retention window, and saves it. */
    public Snapshot takeAndStore(Player player) {
        Snapshot snapshot = liveSnapshotOf(player);
        List<Snapshot> kept = retentionRule.applying(
                store.load(player.getUniqueId()), snapshot, settings.retentionCountClamped());
        if (!store.saveAll(player.getUniqueId(), kept)) {
            log.warn("Could not save a snapshot for {}.", player.getName());
        }
        return snapshot;
    }

    /**
     * What this player is carrying right now, shaped exactly like a stored one — not saved anywhere.
     * What a compare screen diffs a snapshot against, and what a fresh {@code takeAndStore} would
     * write if called this instant.
     */
    public Snapshot liveSnapshotOf(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new Snapshot(player.getUniqueId(), player.getName(), Instant.now(),
                encodedOf(inventory.getContents()), encodedOf(inventory.getArmorContents()),
                ItemText.encode(inventory.getItemInOffHand()));
    }

    /** This player's whole stored history, newest first — the order a browse screen shows it in. */
    public List<Snapshot> historyOf(UUID playerId) {
        List<Snapshot> found = new ArrayList<>(store.load(playerId));
        found.sort(Comparator.comparing(Snapshot::takenAt).reversed());
        return found;
    }

    /**
     * Every player this server has a snapshot of, newest activity first — what the root browse
     * screen opens on bare {@code /invsnap}, for the times an admin does not already know a name.
     */
    public List<TrackedPlayer> tracked() {
        List<TrackedPlayer> found = new ArrayList<>();
        for (UUID playerId : store.knownPlayerIds()) {
            List<Snapshot> history = historyOf(playerId);
            if (history.isEmpty()) {
                continue;
            }
            Snapshot newest = history.get(0);
            found.add(new TrackedPlayer(playerId, newest.playerName(), history.size(), newest.takenAt()));
        }
        found.sort(Comparator.comparing(TrackedPlayer::newest).reversed());
        return found;
    }

    /**
     * Writes a stored snapshot back into a live inventory, replacing every one of the three parts
     * it covers. Nothing outside main inventory, armour and off hand is touched.
     */
    public void restore(Player target, Snapshot snapshot) {
        PlayerInventory inventory = target.getInventory();
        inventory.setContents(itemsOf(snapshot.mainInventory()));
        inventory.setArmorContents(itemsOf(snapshot.armor()));
        inventory.setItemInOffHand(ItemText.decode(snapshot.offHand()));
        log.info("Restored a snapshot from {} into {}'s live inventory.",
                snapshot.takenAt(), target.getName());
    }

    /** Every slot, empty ones included — see {@link Snapshot}'s own javadoc for why. */
    private static List<String> encodedOf(ItemStack[] slots) {
        List<String> encoded = new ArrayList<>(slots.length);
        for (ItemStack slot : slots) {
            String line = ItemText.encode(slot);
            encoded.add(line == null ? Snapshot.EMPTY_SLOT : line);
        }
        return encoded;
    }

    private static ItemStack[] itemsOf(List<String> slots) {
        ItemStack[] items = new ItemStack[slots.size()];
        Arrays.setAll(items, index -> ItemText.decode(slots.get(index)));
        return items;
    }
}
