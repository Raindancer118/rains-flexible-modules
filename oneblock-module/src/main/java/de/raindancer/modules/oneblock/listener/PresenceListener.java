package de.raindancer.modules.oneblock.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.store.PlayerSpot;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Coming and going on the island server. Where somebody leaves an island from is kept — the world may be unloaded by
 * the time they are back, and the server would put them at the main spawn — and a death on an island respawns on it
 * rather than in the waiting world.
 */
public final class PresenceListener implements IOneBlockListener {

    private final Plugin plugin;
    private final IslandServer islands;

    public PresenceListener(Plugin plugin, IslandServer islands) {
        this.plugin = plugin;
        this.islands = islands;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Location at = player.getLocation();
        String world = at.getWorld().getName();
        forget(player.getUniqueId());
        if (!Island.isIslandWorld(world)) {
            return;
        }
        PlayerSpot spot = new PlayerSpot(world, at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Scheduling.async(plugin, () -> islands.storage().savePosition(id, spot, now));
    }

    /** No bed or anchor of their own: back to the island they died on, as long as they may be there. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Player player = event.getPlayer();
        String world = player.getWorld().getName();
        Optional<Island> here = islands.book().ofWorld(world);
        Optional<IslandDimension> dimension = Island.dimensionOf(world);
        Optional<Location> back = Optional.empty();
        if (here.isPresent() && dimension.isPresent()
                && islands.travel().refusal(player, here.get(), dimension.get()).isEmpty()) {
            back = islands.travel().arrival(here.get(), dimension.get());
        }
        if (back.isEmpty()) {
            back = islands.book().ofPlayer(player.getUniqueId())
                    .flatMap(own -> islands.travel().arrival(own, IslandDimension.OVERWORLD));
        }
        event.setRespawnLocation(back.orElseGet(() -> islands.travel().waiting()));
    }

    @Override
    public void forget(UUID player) {
        islands.travel().forget(player);
    }
}
