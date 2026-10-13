package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.build.Schematic;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.model.ArenaWorld;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Layout;
import de.raindancer.modules.arena.rules.BorderRule;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The arena's worlds: empty worlds made at runtime, a map built into each from a schematic, a border just past the map,
 * and game rules that keep the map as it was built — no day, no weather, no spreading fire, nothing spawning on its own.
 *
 * <p>Creating a world only works off Folia (Folia has no runtime worlds), and only on the global thread.
 */
public final class ArenaWorldService implements IArenaService {

    /** The height a map's floor is built at. */
    public static final int MAP_Y = 64;

    /** What building a world came to. */
    public record Built(ArenaWorld world, int blocks, java.util.List<String> leftOut) {
    }

    private final Plugin plugin;
    private final LogChannel log;
    private final Path maps;
    private final BorderRule borders = new BorderRule();
    private final java.util.Set<String> locked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile ArenaSettings settings;

    public ArenaWorldService(Plugin plugin, LogChannel log, Path maps, ArenaSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.maps = maps;
        this.settings = settings;
    }

    @Override
    public void settings(ArenaSettings settings) {
        this.settings = settings;
    }

    public Path maps() {
        return maps;
    }

    /** Loads (or makes) every arena world of the layout and puts its rules and border on. Global thread. */
    public void loadAll(Layout layout) {
        for (ArenaWorld arena : layout.worlds()) {
            World world = load(arena.name());
            if (world == null) {
                log.error("Arena world '{}' could not be loaded.", arena.name());
                continue;
            }
            prepare(world, arena);
        }
    }

    /** Loads an existing world or makes an empty one. Null when the server will not. */
    public World load(String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) {
            return world;
        }
        try {
            // The generator has to be given again on every load, not only the first: without it the server
            // generates ordinary terrain for every chunk the map did not reach.
            return new WorldCreator(name).generator(new VoidGenerator()).type(WorldType.FLAT)
                    .generateStructures(false).environment(World.Environment.NORMAL).createWorld();
        } catch (RuntimeException failure) {
            log.error(failure, "The server refused to make the arena world '{}'.", name);
            return null;
        }
    }

    private volatile java.util.function.Predicate<org.bukkit.entity.Player> fighterBuilds = player -> false;
    private volatile java.util.function.Predicate<org.bukkit.Location> pitOpen = where -> false;

    /** Who may build in the locked arena worlds beyond admins, and which places are open to explosions — set once the fights exist. */
    public void building(java.util.function.Predicate<org.bukkit.entity.Player> fighter,
                         java.util.function.Predicate<org.bukkit.Location> open) {
        this.fighterBuilds = fighter;
        this.pitOpen = open;
    }

    /** Rules, time, weather and border — re-applied at every start, so a hand-changed rule does not stick. */
    public void prepare(World world, ArenaWorld arena) {
        // Every block and entity of the map stays as built: RainsCore enforces it; an admin in creative builds.
        // A fighter whose kit builds may also change blocks, and the pit they fight in is open to explosions and fire while
        // it is copied — the arena puts it back (PitRestorer).
        de.raindancer.core.world.protection.LockedWorlds.lock(plugin, world.getName(),
                player -> de.raindancer.modules.arena.util.Builders.isBuilding(player) || fighterBuilds.test(player),
                where -> pitOpen.test(where));
        locked.add(world.getName());
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_WARDEN_SPAWNING, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_TILE_DROPS, false);
        world.setGameRule(GameRule.DO_MOB_LOOT, false);
        world.setGameRule(GameRule.DO_VINES_SPREAD, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        // The arena never lets a death happen; this is only the last line if something else lets one through.
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setAutoSave(true);

        BorderRule.Border border = borders.border(arena, settings.marginClamped());
        WorldBorder edge = world.getWorldBorder();
        edge.setCenter(border.centreX(), border.centreZ());
        edge.setSize(border.size());
        edge.setWarningDistance(2);
        edge.setDamageAmount(0);
    }

    /** The schematic files in the maps folder, by name. */
    public java.util.List<String> mapFiles() {
        try (var files = Files.list(maps)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".schem")).sorted().toList();
        } catch (IOException noFolder) {
            return java.util.List.of();
        }
    }

    /**
     * Makes the world {@code name} (or uses the one there is) and builds {@code mapFile} into it, centred on 0/0 with
     * its floor at {@link #MAP_Y}. The schematic is read off the server thread.
     *
     * @param done told on the global thread — with the built world, or empty and a reason
     */
    public void build(String name, String mapFile, Consumer<Built> done, Consumer<String> failed) {
        Path file = maps.resolve(mapFile).normalize();
        if (!file.startsWith(maps.normalize()) || !Files.isRegularFile(file)) {
            failed.accept("arena.admin.no-such-map");
            return;
        }
        Scheduling.async(plugin, () -> {
            Schematic schematic;
            try {
                schematic = Schematic.read(file);
            } catch (IOException unreadable) {
                log.error("The map {} could not be read: {}", mapFile, unreadable.getMessage());
                Scheduling.global(plugin, () -> failed.accept("arena.admin.map-unreadable"));
                return;
            }
            Scheduling.global(plugin, () -> {
                World world = load(name);
                if (world == null) {
                    failed.accept("arena.admin.world-refused");
                    return;
                }
                int originX = -schematic.width() / 2;
                int originZ = -schematic.length() / 2;
                Box box = new Box(world.getName(), originX, MAP_Y, originZ, originX + schematic.width() - 1,
                        MAP_Y + schematic.height() - 1, originZ + schematic.length() - 1);
                ArenaWorld arena = new ArenaWorld(world.getName(), mapFile, box);
                prepare(world, arena);
                PasteJob job = new PasteJob(plugin, world,
                        schematic.placements(new Spot(world.getName(), originX, MAP_Y, originZ)));
                log.info("Building {} into '{}': {} chunk(s).", mapFile, world.getName(), job.chunks());
                job.start(blocks -> {
                    world.setSpawnLocation((int) Math.floor(box.centreX()), box.maxY() + 1,
                            (int) Math.floor(box.centreZ()));
                    log.info("'{}' is built: {} block(s).", world.getName(), blocks);
                    done.accept(new Built(arena, blocks, schematic.foreign()));
                });
            });
        });
    }

    /** When the module stops: the worlds it locked are let go, so a stopped arena holds no lock it cannot answer for. */
    public void unlockAll() {
        locked.forEach(de.raindancer.core.world.protection.LockedWorlds::unlock);
        locked.clear();
    }

    public Optional<World> world(String name) {
        return Optional.ofNullable(Bukkit.getWorld(name));
    }
}
