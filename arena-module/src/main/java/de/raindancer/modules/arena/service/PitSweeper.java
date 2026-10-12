package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.rules.SweepRule;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.function.Predicate;

/**
 * Clearing a pit of what a fight left. The floor box grown a little each way — an arrow stuck in the wall just past the
 * edge, a cloud drifting over it — on the region thread that owns the pit, removing only what {@link SweepRule} clears
 * and never a gladiator.
 */
public final class PitSweeper {

    /** Blocks past the floor box that still count as the pit's mess. */
    private static final int REACH = 4;

    private final Plugin plugin;
    private final Server server;
    private final SweepRule rule = new SweepRule();
    private final Predicate<Entity> gladiator;

    public PitSweeper(Plugin plugin, Server server, Predicate<Entity> gladiator) {
        this.plugin = plugin;
        this.server = server;
        this.gladiator = gladiator;
    }

    public void sweep(Pit pit) {
        Box floor = pit.floor();
        World world = floor == null ? null : server.getWorld(floor.world());
        if (world == null) {
            return;
        }
        BoundingBox area = new BoundingBox(floor.minX() - REACH, floor.minY() - REACH, floor.minZ() - REACH,
                floor.maxX() + 1 + REACH, floor.maxY() + 1 + REACH, floor.maxZ() + 1 + REACH);
        Location centre = new Location(world, floor.centreX(), floor.minY(), floor.centreZ());
        Scheduling.region(plugin, centre, () -> {
            for (Entity entity : world.getNearbyEntities(area)) {
                if (rule.clears(entity.getType().name()) && !gladiator.test(entity)) {
                    entity.remove();
                }
            }
        });
    }
}
