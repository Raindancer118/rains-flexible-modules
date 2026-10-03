package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Counts the portals everybody in a hunt takes, for their own numbers — the one thing about a hunt's
 * moments that is Manhunt's alone. The milestones themselves (the Nether, a fortress, the dragon…)
 * are the run's own splits, which the lobby takes; in a hunt only a Runner still running counts for
 * them, through {@code ManhuntMode.countsForGoal}.
 *
 * <p>Registered for exactly one hunt through {@code SpeedrunRun.listen}.
 */
public final class HuntRecorder implements Listener {

    private final Hunt hunt;
    private final Consumer<UUID> portal;

    public HuntRecorder(Hunt hunt, Consumer<UUID> portal) {
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.portal = Objects.requireNonNull(portal, "portal");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (hunt.everybody().contains(event.getPlayer().getUniqueId())) {
            portal.accept(event.getPlayer().getUniqueId());
        }
    }

    public String describe() {
        return "every portal somebody in a hunt takes";
    }
}
