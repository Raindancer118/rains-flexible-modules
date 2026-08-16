package de.raindancer.modules.invsnap.service;

import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.rules.SnapshotDueRule;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Decides, once a second, whether each online player is due another automatic snapshot.
 *
 * <p>Asked on a short fixed timer rather than scheduled directly at the configured interval — see
 * {@link SnapshotDueRule}'s own javadoc — so a changed interval takes effect on its own next tick
 * instead of only after a restart. A player with no recorded snapshot yet (just joined, or the
 * module only just started) is due immediately, which is what puts a first snapshot on record
 * without waiting a full interval.
 */
public final class AutoSnapshotService implements IInvSnapService {

    private final SnapshotService snapshots;
    private final SnapshotDueRule dueRule;
    private final ConcurrentMap<UUID, Instant> lastSnapshotAt = new ConcurrentHashMap<>();
    private volatile InvSnapSettings settings;

    public AutoSnapshotService(SnapshotService snapshots, SnapshotDueRule dueRule,
                               InvSnapSettings settings) {
        this.snapshots = snapshots;
        this.dueRule = dueRule;
        this.settings = settings;
    }

    @Override
    public void settings(InvSnapSettings settings) {
        this.settings = settings;
    }

    /** Called once a second with who is online right now; takes a snapshot of whoever is due one. */
    public void tick(Iterable<? extends Player> online, Instant now) {
        for (Player player : online) {
            UUID id = player.getUniqueId();
            Instant last = lastSnapshotAt.getOrDefault(id, Instant.EPOCH);
            if (dueRule.isDue(last, now, settings.snapshotInterval())) {
                snapshots.takeAndStore(player);
                lastSnapshotAt.put(id, now);
            }
        }
    }

    /** So a player who leaves and rejoins is due one on their own schedule, not the server's uptime. */
    public void forget(UUID playerId) {
        lastSnapshotAt.remove(playerId);
    }
}
