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
        } catch (RuntimeException broken) {
            services.log().warn("Could not check {}'s cosmetics on join: {}",
                    event.getPlayer().getName(), broken.getMessage());
        }
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody: the style lives in Core's identities.
    }
}
