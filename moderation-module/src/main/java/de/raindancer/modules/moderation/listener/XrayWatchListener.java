package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.OreKind;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands every block a player digs to the x-ray evidence, while it still stands. Ore a player placed
 * themselves is not mining when it comes back out, and staff with the bypass are not watched.
 */
public final class XrayWatchListener implements IModerationListener {

    private final ModerationServices services;
    private final Set<Key> placedOre = ConcurrentHashMap.newKeySet();

    private record Key(UUID world, int x, int y, int z) {
    }

    public XrayWatchListener(ModerationServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (OreKind.of(event.getBlock().getType()).isPresent()) {
            placedOre.add(keyOf(event.getBlock().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (placedOre.remove(keyOf(block.getLocation())) || player.hasPermission(SuspiciousCommandListener.BYPASS)) {
            return;
        }
        services.xrayDetection().dug(player, block);
    }

    private static Key keyOf(Location location) {
        return new Key(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public void forget(UUID player) {
        services.xrayDetection().forget(player);
    }

    @Override
    public String describe() {
        return "handing every dug block to the x-ray evidence";
    }
}
