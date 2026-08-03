package de.raindancer.modules.claims;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes a player from a claim: walks outward to the nearest point beyond the border, verifies the spot
 * is survivable and teleports them there.
 * <p>
 * Used by {@code /claim kick}, by ban and timeout enforcement, and by the movement listener when a
 * banned player tries to walk back in. Nobody is ever dropped into lava or the void — if no safe spot
 * exists just outside the border the fallback chain ends at the world spawn.
 */
public final class EvictionService {

    /** Blocks of clearance kept between the border and the drop-off point. */
    private static final int BORDER_MARGIN = 2;
    /** How far past the bounding box we are willing to search before giving up. */
    private static final int EXTRA_SEARCH = 24;
    /** Vertical search window around the player's own Y. */
    private static final int VERTICAL_SEARCH = 24;

    private final Plugin plugin;
    private final Messages messages;
    /** Suppresses repeated eviction attempts while a teleport is still in flight. */
    private final Map<UUID, Long> recentlyEvicted = new ConcurrentHashMap<>();

    public EvictionService(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public boolean recentlyEvicted(Player player) {
        Long last = recentlyEvicted.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < 1500L;
    }

    public void forget(UUID uuid) {
        recentlyEvicted.remove(uuid);
    }

    /**
     * Evicts the player from the claim.
     *
     * @param messageKey message sent to the player, or {@code null} to stay silent
     */
    public void evict(Player player, Claim claim, String messageKey, Map<String, String> placeholders) {
        evictFrom(player, claim.shape(), messageKey, placeholders);
    }

    /**
     * Evicts the player from any footprint, not only a claim's.
     * <p>
     * A town is not a claim but is escorted out of the same way, so the geometry is the parameter rather
     * than the thing that owns it.
     */
    public void evictFrom(Player player, ClaimShape shape, String messageKey,
                          Map<String, String> placeholders) {
        if (recentlyEvicted(player)) {
            return;
        }
        recentlyEvicted.put(player.getUniqueId(), System.currentTimeMillis());

        Location from = player.getLocation();
        World world = from.getWorld();
        if (world == null) {
            return;
        }
        // Block reads must happen on the region owning those coordinates.
        Scheduling.region(plugin, from, () -> {
            Location target = findExitLocation(world, shape, from).orElseGet(() -> fallback(player, world));
            Scheduling.entity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.teleportAsync(target).thenAccept(success -> {
                    if (success && messageKey != null) {
                        player.sendMessage(messages.prefixed(messageKey, placeholders));
                    }
                });
            });
        });
    }

    public void evict(Player player, Claim claim, String messageKey) {
        evict(player, claim, messageKey, Map.of());
    }

    /**
     * Nearest survivable spot outside the claim footprint.
     * <p>
     * Searches in expanding rings around the player's column so the player ends up just past whichever
     * border they were closest to, keeping their orientation and momentum intact.
     */
    public Optional<Location> findExitLocation(World world, ClaimShape shape, Location from) {
        int originX = from.getBlockX();
        int originZ = from.getBlockZ();
        int maxRadius = Math.max(
                Math.max(shape.maxX() - shape.minX(), shape.maxZ() - shape.minZ()) / 2 + BORDER_MARGIN,
                BORDER_MARGIN) + EXTRA_SEARCH;

        for (int radius = BORDER_MARGIN; radius <= maxRadius; radius++) {
            Optional<Location> found = scanRing(world, shape, from, originX, originZ, radius);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<Location> scanRing(World world, ClaimShape shape, Location from,
                                        int originX, int originZ, int radius) {
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Ring only: skip everything already covered by a smaller radius.
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                int x = originX + dx;
                int z = originZ + dz;
                if (shape.containsColumn(x, z)) {
                    continue;
                }
                // Keep a margin so the player does not stand with one foot back inside the border.
                if (isWithinMargin(shape, x, z)) {
                    continue;
                }
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Optional<Integer> safeY = findSafeY(world, x, from.getBlockY(), z);
                if (safeY.isEmpty()) {
                    continue;
                }
                Location candidate = new Location(world, x + 0.5D, safeY.get(), z + 0.5D,
                        from.getYaw(), from.getPitch());
                double distance = candidate.distanceSquared(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean isWithinMargin(ClaimShape shape, int x, int z) {
        for (int dx = -BORDER_MARGIN; dx <= BORDER_MARGIN; dx++) {
            for (int dz = -BORDER_MARGIN; dz <= BORDER_MARGIN; dz++) {
                if (shape.containsColumn(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Y closest to {@code preferredY} where a player can stand unharmed. */
    private Optional<Integer> findSafeY(World world, int x, int preferredY, int z) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        for (int offset = 0; offset <= VERTICAL_SEARCH; offset++) {
            int down = preferredY - offset;
            if (down >= min && down <= max && isSafeStanding(world, x, down, z)) {
                return Optional.of(down);
            }
            int up = preferredY + offset;
            if (offset > 0 && up >= min && up <= max && isSafeStanding(world, x, up, z)) {
                return Optional.of(up);
            }
        }
        // Nothing near the player's altitude — try the surface as a last resort for this column.
        int surface = world.getHighestBlockYAt(x, z) + 1;
        if (surface >= min && surface <= max && isSafeStanding(world, x, surface, z)) {
            return Optional.of(surface);
        }
        return Optional.empty();
    }

    /** Solid floor, two blocks of breathing room, nothing that hurts. */
    public boolean isSafeStanding(World world, int x, int y, int z) {
        Block floor = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        if (!floor.getType().isSolid() || isHazard(floor.getType())) {
            return false;
        }
        return passable(feet) && passable(head);
    }

    private boolean passable(Block block) {
        Material type = block.getType();
        if (isHazard(type)) {
            return false;
        }
        return type.isAir() || !block.getType().isSolid();
    }

    private boolean isHazard(Material material) {
        return switch (material) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, SWEET_BERRY_BUSH,
                 POWDER_SNOW, WITHER_ROSE, END_PORTAL, NETHER_PORTAL, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    /** Bed / respawn anchor, else the world spawn. */
    private Location fallback(Player player, World world) {
        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) {
            return respawn;
        }
        return world.getSpawnLocation();
    }
}
