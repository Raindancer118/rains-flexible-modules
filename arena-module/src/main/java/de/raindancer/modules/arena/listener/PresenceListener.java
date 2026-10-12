package de.raindancer.modules.arena.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.ArenaServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** Whoever arrives from the survival server starts in the hub with empty hands. */
public final class PresenceListener implements IArenaListener {

    private final ArenaServices services;

    public PresenceListener(ArenaServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A tick later, on their own thread: the join is not the moment to teleport.
        Scheduling.entityLater(services.plugin(), player, 1L, () -> {
            if (player.isOnline()) {
                services.arena().arrived(player);
            }
        });
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered about anybody here.
    }
}
