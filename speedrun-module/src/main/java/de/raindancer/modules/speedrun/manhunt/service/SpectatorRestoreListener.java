package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Nobody is left a spectator by a hunt that is no longer happening.
 *
 * <h2>The case this exists for</h2>
 * A Runner is caught, made a spectator, and logs out. The server restarts — or the hunt is reset from
 * the console, or the plugin is reloaded — and the hunt they were in no longer exists anywhere. They
 * log back in able to fly through walls and touch nothing, for good, and nothing in the game explains
 * why. This is registered for the whole life of the module, not for a hunt, precisely because the hunt
 * is the thing that may be gone.
 *
 * <p>Somebody still eliminated in a hunt that is still going is left watching — and a Runner caught
 * while they were logged out ({@code AbsentRunners}) is made to watch the moment they come back.
 */
public final class SpectatorRestoreListener implements Listener {

    private final Supplier<Optional<Hunt>> liveHunt;
    private final Eliminations eliminations;

    public SpectatorRestoreListener(Supplier<Optional<Hunt>> liveHunt, Eliminations eliminations) {
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.eliminations = Objects.requireNonNull(eliminations, "eliminations");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean marked = eliminations.isMarked(player);
        boolean stillOut = liveHunt.get()
                .map(hunt -> hunt.isEliminated(player.getUniqueId()))
                .orElse(false);
        if (marked && !stillOut) {
            eliminations.restore(player);
        } else if (!marked && stillOut) {
            eliminations.spectate(player);
        }
    }

    /**
     * Everybody already online when the module comes up — a reload has no join to wait for. No hunt
     * survives one, so every mark is a leftover.
     */
    public void sweep(Collection<? extends Player> online) {
        for (Player player : online) {
            if (eliminations.isMarked(player)) {
                eliminations.restoreOnTheirThread(player);
            }
        }
    }

    public String describe() {
        return "putting a Runner back who was left spectating a hunt that has ended";
    }
}
