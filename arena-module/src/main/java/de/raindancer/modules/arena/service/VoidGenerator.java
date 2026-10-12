package de.raindancer.modules.arena.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * Nothing at all: an arena world is the map and empty air round it. Overriding none of the generation steps is what
 * makes a chunk generator generate nothing — no terrain, no caves, no decorations, no mobs.
 */
public final class VoidGenerator extends ChunkGenerator {

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 80, 0.5);
    }
}
