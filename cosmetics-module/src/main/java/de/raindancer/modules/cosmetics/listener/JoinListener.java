package de.raindancer.modules.cosmetics.listener;

import de.raindancer.modules.cosmetics.CosmeticsServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * Takes off a name style somebody may no longer wear, as they join; their particle and teleport look are
 * checked by {@link de.raindancer.modules.cosmetics.service.DataWatch} once their data is on them. Never stops
 * anybody joining.
 */
public final class JoinListener implements ICosmeticsListener {

    private final CosmeticsServices services;

    public JoinListener(CosmeticsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (services.core().linkFollows()) {
            // What they may wear is decided on survival; their choices come over from there (RainsCore's link).
            return;
        }
        try {
            services.names().revalidate(event.getPlayer());
        } catch (RuntimeException broken) {
            services.log().warn("Could not check {}'s cosmetics on join: {}",
                    event.getPlayer().getName(), broken.getMessage());
        }
    }

    /** A teleport choice is held in memory only while they are here; their data keeps it for next time. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.teleports().forget(player);
        services.watch().forget(player);
    }
}
