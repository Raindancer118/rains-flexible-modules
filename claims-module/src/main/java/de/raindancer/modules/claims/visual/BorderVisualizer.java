package de.raindancer.modules.claims.visual;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.NoClaimZone;
import de.raindancer.modules.claims.selection.Selection;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws claim borders for a single player, either with particles, with client side fake blocks, or both.
 * <p>
 * Everything is per player: particles are sent with {@link Player#spawnParticle} and blocks with
 * {@link Player#sendBlockChange}, so nobody else sees the outline and the world is never modified.
 * <p>
 * Folia safety: the particle loop runs on the player's entity scheduler, and the block pass groups
 * columns by chunk and hops to the owning region before reading world state.
 */
public final class BorderVisualizer {

    /** Colours and blocks used for one kind of outline. */
    public record Palette(Color particleColor, Material edgeBlock, Material cornerBlock) {
    }

    private static final class Session {
        ScheduledTask particleTask;
        final Map<Location, BlockData> fakeBlocks = new HashMap<>();
        long endsAt;
    }

    /**
     * The persistent glow on the blocks a player has clicked during a selection.
     * <p>
     * Kept in its own channel rather than in {@link Session}, because a temporary border preview must not
     * wipe the markers — the whole point is that they stay visible for as long as the selection exists.
     */
    private static final class Markers {
        ScheduledTask task;
        final Map<Location, BlockData> replaced = new HashMap<>();
    }

    private final Plugin plugin;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Markers> markers = new ConcurrentHashMap<>();

    public BorderVisualizer(Plugin plugin, ClaimSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    public Palette claimPalette() {
        return new Palette(Color.fromRGB(0x4F, 0xC3, 0xF7), settings.visualEdgeBlock(), settings.visualCornerBlock());
    }

    public Palette ownClaimPalette() {
        return new Palette(Color.fromRGB(0x7C, 0xE8, 0x7C), settings.visualEdgeBlock(), settings.visualCornerBlock());
    }

    public Palette selectionPalette() {
        return new Palette(Color.fromRGB(0xFF, 0xD5, 0x4F), Material.YELLOW_STAINED_GLASS, Material.GLOWSTONE);
    }

    /** Amethyst rather than the claim blue, so a town border is not mistaken for somebody's house. */
    public Palette townPalette() {
        return new Palette(Color.fromRGB(0xB3, 0x8A, 0xE8), Material.PURPLE_STAINED_GLASS,
                Material.AMETHYST_BLOCK);
    }

    public Palette zonePalette() {
        return new Palette(Color.fromRGB(0xE5, 0x39, 0x35), Material.RED_STAINED_GLASS, settings.visualZoneBlock());
    }

    // ------------------------------------------------------------ public entry points

    public void showClaim(Player player, Claim claim, int seconds) {
        Palette palette = claim.isOwner(player.getUniqueId()) ? ownClaimPalette() : claimPalette();
        show(player, claim.worldId(), claim.shape(), palette, seconds);
    }


    public void showZone(Player player, NoClaimZone zone, int seconds) {
        show(player, zone.worldId(), zone.shape(), zonePalette(), seconds);
    }

    /** Renders a selection preview; incomplete selections fall back to marking the clicked points. */
    public void showSelection(Player player, Selection selection, int[] verticalRange, int seconds) {
        if (selection.pointCount() == 0) {
            return;
        }
        if (selection.isComplete()) {
            show(player, selection.worldId(), selection.toShape(verticalRange[0], verticalRange[1]),
                    selectionPalette(), seconds);
            return;
        }
        markPoints(player, selection, verticalRange, seconds);
    }

    // ------------------------------------------------------------ selection markers

    /**
     * Lights up every block the player has clicked so far and keeps it lit.
     * <p>
     * The clicked block itself is swapped for a light-emitting block on the client only, with a bright
     * particle beacon on top — so a corner is unmistakable even from a distance and through terrain.
     * Called again after every click; the previous markers are replaced, never accumulated.
     */
    public void showSelectionMarkers(Player player, Selection selection) {
        clearMarkers(player);
        if (selection.pointCount() == 0) {
            return;
        }
        World world = plugin.getServer().getWorld(selection.worldId());
        if (world == null || !world.getUID().equals(player.getWorld().getUID())) {
            return;
        }

        Markers active = new Markers();
        markers.put(player.getUniqueId(), active);

        List<ClaimPoint> points = selection.points();
        Material marker = settings.selectionMarkerBlock();

        // Group by chunk: reading the terrain height has to happen on the owning region.
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (int index = 0; index < points.size(); index++) {
            ClaimPoint point = points.get(index);
            byChunk.computeIfAbsent(ClaimShape.chunkKey(point.x() >> 4, point.z() >> 4),
                    key -> new ArrayList<>()).add(index);
        }

        for (List<Integer> indices : byChunk.values()) {
            ClaimPoint first = points.get(indices.get(0));
            Location anchor = new Location(world, first.x() + 0.5D, 64, first.z() + 0.5D);
            Scheduling.region(plugin, anchor, () -> {
                if (!player.isOnline() || markers.get(player.getUniqueId()) != active) {
                    return;
                }
                for (int index : indices) {
                    ClaimPoint point = points.get(index);
                    if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                        continue;
                    }
                    int y = selection.clickedYAt(index).orElseGet(
                            () -> world.getHighestBlockYAt(point.x(), point.z()));
                    Location location = new Location(world, point.x(), y, point.z());
                    active.replaced.put(location, world.getBlockAt(location).getBlockData());
                    player.sendBlockChange(location, marker.createBlockData());
                }
            });
        }

        // A slow beacon of particles above each corner, numbered by height so the order is readable.
        Particle.DustOptions dust = new Particle.DustOptions(selectionPalette().particleColor(), 1.3f);
        Particle.DustOptions firstDust = new Particle.DustOptions(Color.LIME, 1.6f);
        active.task = Scheduling.entityTimer(plugin, player, 1L, 12L, task -> {
            if (!player.isOnline() || markers.get(player.getUniqueId()) != active) {
                task.cancel();
                return;
            }
            for (int index = 0; index < points.size(); index++) {
                ClaimPoint point = points.get(index);
                int baseY = selection.clickedYAt(index).orElse(player.getLocation().getBlockY());
                // The beacon grows with the corner number, so you can tell the corners apart.
                int beaconHeight = 2 + Math.min(6, index);
                for (int offset = 1; offset <= beaconHeight; offset++) {
                    player.spawnParticle(Particle.DUST, point.x() + 0.5D, baseY + offset + 0.5D,
                            point.z() + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D,
                            index == 0 ? firstDust : dust);
                }
            }
        });
    }

    /** Removes the selection glow and restores the real blocks for the player. */
    public void clearMarkers(Player player) {
        Markers active = markers.remove(player.getUniqueId());
        if (active == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
        }
        if (player.isOnline()) {
            active.replaced.forEach(player::sendBlockChange);
        }
    }

    public boolean hasMarkers(Player player) {
        return markers.containsKey(player.getUniqueId());
    }

    public void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.particleTask != null) {
            session.particleTask.cancel();
        }
        revert(player, session);
    }

    public void forget(UUID uuid) {
        Session session = sessions.remove(uuid);
        if (session != null && session.particleTask != null) {
            session.particleTask.cancel();
        }
        Markers active = markers.remove(uuid);
        if (active != null && active.task != null) {
            active.task.cancel();
        }
    }

    public boolean isShowing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------ rendering

    private void show(Player player, UUID worldId, ClaimShape shape, Palette palette, int seconds) {
        World world = plugin.getServer().getWorld(worldId);
        if (world == null || !world.getUID().equals(player.getWorld().getUID())) {
            return;
        }
        stop(player);

        Session session = new Session();
        session.endsAt = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        sessions.put(player.getUniqueId(), session);

        ClaimSettings.VisualMode mode = settings.visualMode();
        if (mode != ClaimSettings.VisualMode.BLOCKS) {
            startParticles(player, shape, palette, session);
        }
        if (mode != ClaimSettings.VisualMode.PARTICLES) {
            placeFakeBlocks(player, world, shape, palette, session);
        }
        // A single delayed cleanup handles both renderers.
        Scheduling.entityLater(plugin, player, Math.max(1, seconds) * 20L, () -> {
            Session current = sessions.get(player.getUniqueId());
            if (current == session) {
                stop(player);
            }
        });
    }

    private void startParticles(Player player, ClaimShape shape, Palette palette, Session session) {
        List<ClaimPoint> outline = OutlineGeometry.outlineColumns(shape, settings.visualSpacing());
        List<ClaimPoint> corners = OutlineGeometry.corners(shape);
        Particle.DustOptions dust = new Particle.DustOptions(palette.particleColor(), 1.0f);
        Particle.DustOptions cornerDust = new Particle.DustOptions(Color.WHITE, 1.4f);
        int radius = settings.visualRadius();
        int radiusSquared = radius * radius;
        int budget = settings.visualMaxPointsPerTick();

        session.particleTask = Scheduling.entityTimer(plugin, player, 1L, 10L, task -> {
            if (!player.isOnline() || System.currentTimeMillis() > session.endsAt) {
                task.cancel();
                return;
            }
            Location eye = player.getLocation();
            int viewerY = eye.getBlockY();
            int drawn = 0;

            List<Integer> ringHeights = OutlineGeometry.ringHeights(shape, viewerY, 4);
            for (ClaimPoint column : outline) {
                if (column.distanceSquared(eye.getBlockX(), eye.getBlockZ()) > radiusSquared) {
                    continue;
                }
                for (int y : ringHeights) {
                    if (drawn++ > budget) {
                        return;
                    }
                    player.spawnParticle(Particle.DUST, column.x() + 0.5D, y + 0.5D, column.z() + 0.5D,
                            1, 0.0D, 0.0D, 0.0D, 0.0D, dust);
                }
            }

            if (!settings.visualShowVerticalPillars()) {
                return;
            }
            List<Integer> pillarHeights = OutlineGeometry.pillarHeights(shape, viewerY, radius / 2,
                    settings.visualSpacing());
            for (ClaimPoint corner : corners) {
                if (corner.distanceSquared(eye.getBlockX(), eye.getBlockZ()) > radiusSquared) {
                    continue;
                }
                for (int y : pillarHeights) {
                    if (drawn++ > budget) {
                        return;
                    }
                    player.spawnParticle(Particle.DUST, corner.x() + 0.5D, y + 0.5D, corner.z() + 0.5D,
                            1, 0.0D, 0.0D, 0.0D, 0.0D, cornerDust);
                }
            }
        });
    }

    private void placeFakeBlocks(Player player, World world, ClaimShape shape, Palette palette, Session session) {
        List<ClaimPoint> outline = OutlineGeometry.outlineColumns(shape, Math.max(1, settings.visualSpacing()));
        List<ClaimPoint> corners = OutlineGeometry.corners(shape);
        int radius = settings.visualRadius();
        int radiusSquared = radius * radius;
        int centreX = player.getLocation().getBlockX();
        int centreZ = player.getLocation().getBlockZ();

        // Group by chunk so each region thread is entered exactly once.
        Map<Long, List<ClaimPoint>> byChunk = new HashMap<>();
        for (ClaimPoint column : outline) {
            if (column.distanceSquared(centreX, centreZ) > radiusSquared) {
                continue;
            }
            byChunk.computeIfAbsent(ClaimShape.chunkKey(column.x() >> 4, column.z() >> 4),
                    key -> new ArrayList<>()).add(column);
        }

        for (Map.Entry<Long, List<ClaimPoint>> entry : byChunk.entrySet()) {
            List<ClaimPoint> columns = entry.getValue();
            Location anchor = new Location(world, columns.get(0).x() + 0.5D, shape.minY(), columns.get(0).z() + 0.5D);
            Scheduling.region(plugin, anchor, () -> {
                if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
                    return;
                }
                for (ClaimPoint column : columns) {
                    if (!world.isChunkLoaded(column.x() >> 4, column.z() >> 4)) {
                        continue;
                    }
                    int surfaceY = surfaceInside(world, shape, column);
                    Location location = new Location(world, column.x(), surfaceY, column.z());
                    Material material = corners.contains(column) ? palette.cornerBlock() : palette.edgeBlock();
                    Block block = world.getBlockAt(location);
                    session.fakeBlocks.put(location, block.getBlockData());
                    player.sendBlockChange(location, material.createBlockData());
                }
            });
        }
    }

    /** Highest non-air block inside the claim's Y range, so markers hug the terrain. */
    private int surfaceInside(World world, ClaimShape shape, ClaimPoint column) {
        int highest = world.getHighestBlockYAt(column.x(), column.z());
        if (highest < shape.minY()) {
            return shape.minY();
        }
        if (highest > shape.maxY()) {
            return shape.maxY();
        }
        return highest;
    }

    private void revert(Player player, Session session) {
        if (session.fakeBlocks.isEmpty() || !player.isOnline()) {
            return;
        }
        Map<Location, BlockData> snapshot = new HashMap<>(session.fakeBlocks);
        session.fakeBlocks.clear();
        snapshot.forEach((location, data) -> {
            if (player.isOnline()) {
                player.sendBlockChange(location, data);
            }
        });
    }

    /** Marks the individual clicked points of an unfinished selection. */
    private void markPoints(Player player, Selection selection, int[] verticalRange, int seconds) {
        World world = plugin.getServer().getWorld(selection.worldId());
        if (world == null) {
            return;
        }
        stop(player);
        Session session = new Session();
        session.endsAt = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        sessions.put(player.getUniqueId(), session);

        Particle.DustOptions dust = new Particle.DustOptions(selectionPalette().particleColor(), 1.6f);
        List<ClaimPoint> points = selection.points();
        session.particleTask = Scheduling.entityTimer(plugin, player, 1L, 10L, task -> {
            if (!player.isOnline() || System.currentTimeMillis() > session.endsAt) {
                task.cancel();
                return;
            }
            for (ClaimPoint point : points) {
                for (int y = verticalRange[0]; y <= verticalRange[1]; y += Math.max(2, settings.visualSpacing() * 2)) {
                    player.spawnParticle(Particle.DUST, point.x() + 0.5D, y + 0.5D, point.z() + 0.5D,
                            1, 0.0D, 0.0D, 0.0D, 0.0D, dust);
                }
            }
        });
        Scheduling.entityLater(plugin, player, Math.max(1, seconds) * 20L, () -> {
            if (sessions.get(player.getUniqueId()) == session) {
                stop(player);
            }
        });
    }
}
