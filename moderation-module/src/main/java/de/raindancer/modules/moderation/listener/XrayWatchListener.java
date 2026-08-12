package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.MinedBlock;
import org.bukkit.Location;
import org.bukkit.Material;
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
 * Watching what gets mined, for {@link de.raindancer.modules.moderation.service.XrayDetectionService}.
 *
 * <h2>Why placed ore does not count</h2>
 * A player who places a block of their own ore back down — decoration, a half-finished build, moving a
 * stack between chests through the world — and then breaks it again is not mining anything. Counted as
 * ore mined, it would nudge somebody's ratio up for doing nothing suspicious at all, so every ore block
 * placed is remembered until it is broken, and a break at that exact spot is skipped entirely: neither
 * ore nor stone, because it was not natural mining either way.
 *
 * <h2>Why the remembered set stays small</h2>
 * Only ore blocks are tracked, and only until they are broken again — an ordinary player places very
 * few of these, and the set is never asked to remember a whole build.
 */
public final class XrayWatchListener implements IModerationListener {

    private final ModerationServices services;

    /** Where a player has placed one of the watched ores, keyed by world and coordinates. */
    private final Set<String> placedOre = ConcurrentHashMap.newKeySet();

    public XrayWatchListener(ModerationServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isWatchedOre(event.getBlock().getType())) {
            placedOre.add(keyOf(event.getBlock().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String key = keyOf(block.getLocation());

        if (placedOre.remove(key)) {
            // Their own placement, coming back out. Not mining, in either direction.
            return;
        }
        if (player.hasPermission(SuspiciousCommandListener.BYPASS)) {
            return;
        }
        services.xrayDetection().mined(player.getUniqueId(), player.getName(),
                new MinedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        block.getType().name()));
    }

    private boolean isWatchedOre(Material material) {
        for (String name : services.config().xrayOres()) {
            if (name != null && name.equalsIgnoreCase(material.name())) {
                return true;
            }
        }
        return false;
    }

    private static String keyOf(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    @Override
    public void forget(UUID player) {
        services.xrayDetection().forget(player);
    }

    @Override
    public String describe() {
        return "watching mined blocks for a pattern that looks like x-ray";
    }
}
