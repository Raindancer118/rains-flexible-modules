package de.raindancer.modules.arenagate.listener;

import de.raindancer.modules.arenagate.GateServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Going to the arena is not quitting and coming back is not joining: the usual lines are replaced by "went to the
 * arena" and "is back from the arena".
 */
public final class ArrivalListener implements IGateListener {

    private final GateServices services;

    public ArrivalListener(GateServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (services.network() != null && services.network().isReturning(event.getPlayer().getUniqueId())) {
            event.joinMessage(services.messages().get("arenagate.back", "player", event.getPlayer().getName()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (services.gate().isLeavingForTheArena(event.getPlayer().getUniqueId())) {
            event.quitMessage(services.messages().get("arenagate.went", "player", event.getPlayer().getName()));
        }
    }

    @Override
    public void forget(UUID player) {
        // What is remembered — who is leaving, who is coming back — is taken by the next quit or join.
    }
}
