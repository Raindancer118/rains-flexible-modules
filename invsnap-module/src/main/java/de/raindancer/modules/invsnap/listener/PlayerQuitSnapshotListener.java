package de.raindancer.modules.invsnap.listener;

import de.raindancer.modules.invsnap.service.AutoSnapshotService;
import de.raindancer.modules.invsnap.service.SnapshotService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * A snapshot taken the instant somebody disconnects, and the timer's memory of them cleared.
 *
 * <h2>Why quit takes one of its own, rather than waiting for the timer</h2>
 * The timer only ever sees who is currently online, so the interval between somebody's last
 * automatic snapshot and the moment they log off is otherwise a gap with nothing recorded in it —
 * exactly the moment a dispute about "what I was carrying when I logged off" is about. One more
 * snapshot here closes that gap for the cost of one extra file write per disconnect.
 */
public final class PlayerQuitSnapshotListener implements IInvSnapListener {

    private final SnapshotService snapshots;
    private final AutoSnapshotService autoSnapshots;

    public PlayerQuitSnapshotListener(SnapshotService snapshots, AutoSnapshotService autoSnapshots) {
        this.snapshots = snapshots;
        this.autoSnapshots = autoSnapshots;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        snapshots.takeAndStore(event.getPlayer());
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        autoSnapshots.forget(player);
    }

    @Override
    public String describe() {
        return "a final snapshot on disconnect, and forgetting the timer's memory of them";
    }
}
