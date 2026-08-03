package de.raindancer.modules.claims.listener;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.UUID;

/** Session bookkeeping: name cache, per-player state cleanup and world name refreshes. */
public final class PlayerSessionListener implements IClaimListener {

    @Override
    public String describe() {
        return "joining, leaving, respawning and worlds being loaded";
    }


    private final ClaimServices services;

    public PlayerSessionListener(ClaimServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Names come from Core, which sees every join itself, so there is nothing to remember here.
        services.movement().syncPosition(event.getPlayer());
        services.ambience().track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        services.movement().forget(uuid);
        // Core forgets its own half — the bypass and the refusal throttle — from its quit handler.
        services.provider().forget(uuid);
        services.entryFees().forget(uuid);
        services.visualizer().forget(uuid);
        services.prompts().forget(uuid);
        services.eviction().forget(uuid);
        services.ambience().forget(uuid);
        // A dangling selection would keep a stick alive that the player can no longer use.
        services.selections().clear(uuid);
    }

    /**
     * Re-attaches the per-player loop after a death.
     * <p>
     * Respawning replaces the player's entity, and anything scheduled on the old one is retired with it —
     * so without this the pantry, auto-equip, claim effects and weather all quietly stop working until
     * the player reconnects.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Scheduling.entityLater(services.plugin(), event.getPlayer(), 2L, () -> {
            services.movement().syncPosition(event.getPlayer());
            services.ambience().retrack(event.getPlayer());
        });
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        // Claims store the world UUID, but the cached display name may be stale after a rename.
        services.claimService().refreshWorldNames();
    }
}
