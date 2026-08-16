package de.raindancer.modules.invsnap.listener;

import de.raindancer.modules.invsnap.service.SnapshotService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/**
 * A snapshot taken at the moment somebody dies, so an accidental death can be undone — the
 * inventory restored, whatever the world did with the drops or the keep-inventory rule left alone.
 *
 * <h2>Why {@code MONITOR} still sees the live inventory</h2>
 * Bukkit fires {@link PlayerDeathEvent} before it actually empties a dying player's inventory — the
 * event's own {@code getDrops()} is only a pre-computed copy for whoever handles the drop, and the
 * real clearing (when {@code keepInventory} is off) happens after every listener has run. So a
 * snapshot taken here, even last, is still a snapshot of what the player was carrying the instant
 * before they died — exactly the moment an admin restoring "what I had before that creeper" needs.
 *
 * <p>{@code MONITOR} rather than an earlier priority: this only observes, so it runs after anything
 * that might still change the drops, and it never risks being the thing another plugin's own death
 * handling has to account for.
 */
public final class PlayerDeathSnapshotListener implements IInvSnapListener {

    private final SnapshotService snapshots;

    public PlayerDeathSnapshotListener(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        snapshots.takeAndStore(event.getPlayer());
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered between deaths — every death is judged on the inventory at that moment,
        // not on anything carried over from an earlier one.
    }

    @Override
    public String describe() {
        return "a snapshot the instant somebody dies, so an accidental death can be undone";
    }
}
