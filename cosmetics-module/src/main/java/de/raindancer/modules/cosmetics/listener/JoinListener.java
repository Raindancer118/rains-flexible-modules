package de.raindancer.modules.cosmetics.listener;

import de.raindancer.modules.cosmetics.CosmeticsServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** Takes off a name style or particle somebody may no longer wear, as they join. Never stops anybody joining. */
public final class JoinListener implements ICosmeticsListener {

    private final CosmeticsServices services;

    public JoinListener(CosmeticsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        try {
            services.names().revalidate(event.getPlayer());
            services.particles().revalidate(event.getPlayer());
            services.teleports().load(event.getPlayer());
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
    }
}
