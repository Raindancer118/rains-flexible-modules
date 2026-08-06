package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Finding somewhere in the arena that a thing can actually be put down.
 *
 * <h2>Why this is one class and not one per caller</h2>
 * Supply drops and sponsor beacons both ask the same question — "is there ground at this offset from the
 * middle, with room above it, in a world that is loaded?" — and both were asking it separately in the plugin
 * this is ported from, with two different answers. The drop version checked the highest block and the beacon
 * version checked a fixed Y, so beacons regularly appeared inside hills and drops regularly appeared on top
 * of trees.
 *
 * <p>{@link SupplyDropService.Arena} and {@link SponsorBeaconService.Arena} are the same interface twice
 * over, declared separately because each service states what it needs. This implements both, so there is one
 * answer.
 *
 * <h2>What makes a spot acceptable</h2>
 * <ul>
 *   <li><b>The chunk is already loaded.</b> Nothing here pulls the world in behind it: a drop announced for
 *       a place the server would have to generate is several seconds of freeze, at the exact moment forty
 *       people are running towards it.</li>
 *   <li><b>The ground is solid and is not a leaf or a plant.</b> A crate on leaves falls through them the
 *       moment somebody breaks the tree.</li>
 *   <li><b>There is headroom.</b> Two blocks, so a player can stand next to it — a crate wedged under stone
 *       is one nobody can open.</li>
 *   <li><b>It is not in liquid.</b> A crate in a lake sinks out of sight and a beacon in lava is gone.</li>
 * </ul>
 */
public final class ArenaSites implements SupplyDropService.Arena, SponsorBeaconService.Arena {

    /** How much clear space is needed above the ground for something to be placed on it. */
    public static final int HEADROOM = 2;

    /** How far down from the sky the search starts, when there is no highest block to trust. */
    private static final int LOWEST_SENSIBLE_Y = -60;

    private final ArenaBuildService arena;
    private final Supplier<World> worlds;
    private final java.util.function.Function<String, World> byName;

    public ArenaSites(ArenaBuildService arena, java.util.function.Function<String, World> byName) {
        this.arena = arena;
        this.byName = byName;
        this.worlds = () -> arena.layout().map(layout -> byName.apply(layout.world())).orElse(null);
    }

    @Override
    public Optional<Location> centre() {
        return arena.centre();
    }

    @Override
    public Optional<World> worldNamed(String name) {
        return Optional.ofNullable(byName.apply(name));
    }

    /** A spot for a beacon: the same search, and the {@code onlyOverworld} question does not apply. */
    @Override
    public Optional<Location> siteAt(int dx, int dz) {
        return siteAt(dx, dz, false);
    }

    @Override
    public Optional<Location> siteAt(int dx, int dz, boolean onlyOverworld) {
        World world = worlds.get();
        if (world == null) {
            return Optional.empty();
        }
        if (onlyOverworld && world.getEnvironment() != World.Environment.NORMAL) {
            return Optional.empty();
        }
        Optional<Location> middle = centre();
        if (middle.isEmpty()) {
            return Optional.empty();
        }
        int x = middle.get().getBlockX() + dx;
        int z = middle.get().getBlockZ() + dz;

        // Nothing is generated to answer this. See the class note.
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return Optional.empty();
        }
        int y = world.getHighestBlockYAt(x, z);
        if (y < LOWEST_SENSIBLE_Y) {
            return Optional.empty();
        }

        Block ground = world.getBlockAt(x, y, z);
        if (!isGoodGround(ground)) {
            return Optional.empty();
        }
        for (int above = 1; above <= HEADROOM; above++) {
            Block space = world.getBlockAt(x, y + above, z);
            if (!space.getType().isAir() && !space.isPassable()) {
                return Optional.empty();
            }
        }
        return Optional.of(new Location(world, x + 0.5, y + 1, z + 0.5));
    }

    /**
     * Whether something can stand on this block.
     *
     * <p>Liquid and non-solid are the obvious refusals. Leaves are the one worth naming: they are solid
     * enough to place on and stop being there the moment anybody breaks the tree, which is how the version
     * this replaces produced crates that fell into a forest floor.
     */
    private static boolean isGoodGround(Block ground) {
        Material material = ground.getType();
        if (ground.isLiquid() || !material.isSolid()) {
            return false;
        }
        return !org.bukkit.Tag.LEAVES.isTagged(material);
    }

    /**
     * Putting a supply crate down: a chest, filled, on a base, with whatever marks it.
     *
     * <p>The base is placed <em>under</em> the crate rather than the crate replacing whatever was there.
     * Grass with a chest on it is a chest that falls when the grass is broken; an iron block is what
     * {@code events.supply-drops.base-material} exists to be.
     */
    public static SupplyDropService.Landing landing(java.util.function.BiConsumer<Chest, String> fill) {
        return (site, lootTableKey, settings) -> {
            World world = site.getWorld();
            if (world == null) {
                return;
            }
            Block under = world.getBlockAt(site.getBlockX(), site.getBlockY() - 1, site.getBlockZ());
            under.setType(settings.supplyDropBaseMaterial(), false);

            Block crate = world.getBlockAt(site.getBlockX(), site.getBlockY(), site.getBlockZ());
            crate.setType(Material.CHEST, false);
            if (crate.getState() instanceof Chest chest) {
                fill.accept(chest, lootTableKey);
            }

            if (settings.supplyDropFireworkEnabled()) {
                world.spawnEntity(site, EntityType.FIREWORK_ROCKET);
            }
        };
    }

    /** Whether a player is standing close enough to the middle to be inside the arena at all. */
    public boolean isInTheArena(Player player) {
        return arena.layout()
                .filter(layout -> player.getWorld().getName().equals(layout.world()))
                .isPresent();
    }

    /** Turns a slab-shaped ground block into a full one, so nothing balances on a half-height surface. */
    static void levelOff(Block block) {
        if (block.getBlockData() instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE) {
            slab.setType(Slab.Type.DOUBLE);
            block.setBlockData(slab, false);
        }
    }
}
