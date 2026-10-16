package de.raindancer.modules.ctf.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.ctf.CtfServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Coming and going. On a server of its own ({@code lobby-on-join}) everybody joining is put in the lobby; anywhere
 * else nobody is touched on joining — except to get back what was kept for them, if a crash ended their last visit.
 */
public final class PresenceListener implements ICtfListener {

    private final CtfServices services;

    public PresenceListener(CtfServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduling.entityLater(services.plugin(), player, 5L, () -> {
            if (services.config().get().lobbyOnJoin()) {
                services.rounds().join(player);
            } else {
                services.lobby().recover(player, () -> {
                    if (!services.lobby().isInside(player.getUniqueId()) && services.lobby().restore(player)) {
                        services.messages().send(player, "ctf.join.restored");
                    }
                });
            }
        });
    }

    /** What they carried before stays on disk and comes back at their next join; the round lets go of them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        services.rounds().quit(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.rounds().quit(player);
    }
}
