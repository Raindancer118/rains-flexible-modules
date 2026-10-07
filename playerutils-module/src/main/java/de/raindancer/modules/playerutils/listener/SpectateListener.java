package de.raindancer.modules.playerutils.listener;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Keeps a spectator with whoever they watch across worlds, lets them go when that player leaves, and puts
 * back anybody who left — or crashed — mid-watch the moment they come back.
 */
public final class SpectateListener implements IPlayerUtilsListener {

    private final PlayerUtilsServices services;

    public SpectateListener(PlayerUtilsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (services.spectate().isSpectating(player)) {
            Scheduling.entityLater(services.plugin(), player, 5L, () -> {
                if (player.isOnline()) {
                    services.spectate().stop(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Player watched = event.getPlayer();
        for (UUID id : services.spectate().watchersOf(watched.getUniqueId())) {
            Player viewer = services.server().getPlayer(id);
            if (viewer != null) {
                Scheduling.entityLater(services.plugin(), viewer, 2L,
                        () -> services.spectate().attach(viewer, watched));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player gone = event.getPlayer();
        for (UUID id : services.spectate().watchersOf(gone.getUniqueId())) {
            Player viewer = services.server().getPlayer(id);
            if (viewer != null) {
                services.messages().send(viewer, "playerutils.spectate.target-left",
                        "player", PlayerTargets.shownName(gone));
                Scheduling.onOwner(services.plugin(), viewer, () -> services.spectate().stop(viewer));
            }
        }
        forget(gone.getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.spectate().forget(player);
    }
}
