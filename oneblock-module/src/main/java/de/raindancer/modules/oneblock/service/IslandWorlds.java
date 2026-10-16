package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.core.world.manage.VoidGenerator;
import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandBlock;
import de.raindancer.modules.oneblock.model.IslandDimension;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An island's worlds on this server: made the first time, loaded when somebody goes there, unloaded (saved) when the
 * lifecycle says so. Server thread only — Bukkit creates and unloads worlds nowhere else.
 */
public final class IslandWorlds implements IOneBlockService {

    private final Server server;
    private final LogChannel log;

    public IslandWorlds(Server server, LogChannel log) {
        this.server = server;
        this.log = log;
    }

    /** Nothing about making a world is configured. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    public Optional<World> loaded(Island island, IslandDimension dimension) {
        return Optional.ofNullable(server.getWorld(island.worldName(dimension)));
    }

    public boolean anyLoaded(Island island) {
        return island.unlockedDimensions().stream().anyMatch(dimension -> loaded(island, dimension).isPresent());
    }

    /** Players in any of the island's worlds. */
    public List<Player> playersOn(Island island) {
        List<Player> on = new ArrayList<>();
        for (IslandDimension dimension : island.unlockedDimensions()) {
            loaded(island, dimension).ifPresent(world -> on.addAll(world.getPlayers()));
        }
        return on;
    }

    /**
     * The dimension's world, loaded — made first if it never existed, with the block at its spot. Empty when the
     * dimension is not unlocked or Bukkit would not make the world.
     */
    public Optional<World> load(Island island, IslandDimension dimension) {
        Optional<IslandBlock> block = island.block(dimension);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        Optional<World> already = loaded(island, dimension);
        if (already.isPresent()) {
            return already;
        }
        String name = island.worldName(dimension);
        boolean fresh = !folderOf(name).exists();
        World world = new WorldCreator(name)
                .environment(dimension.environment())
                .generator(VoidGenerator.withBiome(dimension.biome()))
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            log.warn("Bukkit would not make the world {} of {}'s island.", name, island.ownerName());
            return Optional.empty();
        }
        if (dimension == IslandDimension.END) {
            disarmTheDragon(world);
        }
        BlockSpot spot = block.get().spot();
        world.setSpawnLocation(spot.x(), spot.y() + 1, spot.z());
        Block at = world.getBlockAt(spot.x(), spot.y(), spot.z());
        if (fresh || at.getType().isAir()) {
            // An explosion, a piston or an unload mid-break can leave the recorded block missing; it cannot be broken
            // or count again until something stands there.
            at.setType(startingBlock(dimension), false);
            if (!fresh) {
                log.info("Put back the missing one block of {} in {}.", island.ownerName(), name);
            }
        }
        if (fresh) {
            log.info("Made {} for {}'s island.", name, island.ownerName());
        }
        return Optional.of(world);
    }

    /** What a fresh block of the dimension is: grass reads as an island at a glance, plain dirt as nothing. */
    public static Material startingBlock(IslandDimension dimension) {
        return dimension.icon();
    }

    /**
     * An end world starts a dragon fight on its first tick: a dragon over somebody's island, and an exit portal built
     * at 0/0. Marked as already won before that tick, there is neither a dragon nor a fight.
     */
    private void disarmTheDragon(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            return;
        }
        battle.setPreviouslyKilled(true);
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null) {
            dragon.remove();
        }
        battle.getBossBar().removeAll();
    }

    /**
     * Saves and unloads every world of the island; anybody still on one is sent to {@code away} first.
     *
     * @return whether every loaded world went
     */
    public boolean unload(Island island, Location away) {
        boolean all = true;
        for (IslandDimension dimension : IslandDimension.values()) {
            Optional<World> world = loaded(island, dimension);
            if (world.isEmpty()) {
                continue;
            }
            for (Player player : List.copyOf(world.get().getPlayers())) {
                player.teleport(away);
            }
            if (!server.unloadWorld(world.get(), true)) {
                log.warn("Could not unload {}.", world.get().getName());
                all = false;
            }
        }
        return all;
    }

    /**
     * Where a world of this name keeps its files. Since 26.1 a world is a dimension inside the main level, not a
     * folder beside it.
     */
    public File folderOf(String name) {
        World main = server.getWorlds().getFirst();
        File namespace = main.getWorldFolder().getParentFile();
        return namespace == null ? new File(server.getWorldContainer(), name) : new File(namespace, name);
    }
}
