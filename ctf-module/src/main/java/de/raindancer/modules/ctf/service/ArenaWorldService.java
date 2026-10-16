package de.raindancer.modules.ctf.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.build.PasteJob;
import de.raindancer.core.world.build.Schematic;
import de.raindancer.core.world.manage.VoidGenerator;
import de.raindancer.core.world.protection.LockedWorlds;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.CtfMap;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The world every map stands in. Each map gets a slot of its own — a stretch of the world far from the others — and is
 * built there once; after that a round only has to put back what was changed in it ({@code Rewind}). Which map stands in
 * which slot, built from which version of its schematic, is kept in {@code built.yml}, so a restart builds nothing again
 * and a changed schematic is built anew.
 */
public final class ArenaWorldService implements ICtfService {

    /** The height a map's lowest layer is built at. */
    public static final int MAP_Y = 64;

    /** Blocks between two slots' starts — wider than any map anybody would play CTF on. */
    public static final int SLOT_WIDTH = 1024;

    /** Where a built map stands. */
    public record Built(String id, int originX, int originY, int originZ) {
    }

    private final Plugin plugin;
    private final LogChannel log;
    private final Path maps;
    private final YamlStore builtFile;
    private final Map<String, Built> built = new ConcurrentHashMap<>();
    private final Map<String, Long> builtVersions = new ConcurrentHashMap<>();
    private final Map<String, PasteJob> building = new ConcurrentHashMap<>();
    private volatile CtfSettings settings;
    private volatile Predicate<Player> mayBuild = player -> false;

    public ArenaWorldService(Plugin plugin, LogChannel log, Path maps, Path builtFile, CtfSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.maps = maps;
        this.builtFile = new YamlStore(builtFile);
        this.settings = settings;
        var yaml = this.builtFile.read();
        var section = yaml.getConfigurationSection("maps");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                built.put(id, new Built(id, section.getInt(id + ".x"), section.getInt(id + ".y", MAP_Y),
                        section.getInt(id + ".z")));
                builtVersions.put(id, section.getLong(id + ".version"));
            }
        }
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public String worldName() {
        return settings.world();
    }

    public boolean isArenaWorld(String world) {
        return settings.world().equals(world);
    }

    /** Who may change blocks in the arena world beyond staff in creative — players in a round that allows it. */
    public void building(Predicate<Player> who) {
        this.mayBuild = who;
    }

    /** Loads or makes the arena world and puts its rules on. Global thread. */
    public Optional<World> world() {
        World world = Bukkit.getWorld(settings.world());
        if (world == null) {
            try {
                world = new WorldCreator(settings.world()).generator(VoidGenerator.empty()).type(WorldType.FLAT)
                        .generateStructures(false).environment(World.Environment.NORMAL).createWorld();
            } catch (RuntimeException refused) {
                log.error(refused, "The server refused to make the Capture the Flag world '{}'.", settings.world());
                return Optional.empty();
            }
        }
        if (world != null) {
            prepare(world);
        }
        return Optional.ofNullable(world);
    }

    private void prepare(World world) {
        if (!LockedWorlds.isLocked(world.getName())) {
            LockedWorlds.lock(plugin, world.getName(),
                    player -> isStaffBuilding(player) || mayBuild.test(player), where -> false);
        }
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
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setDifficulty(Difficulty.NORMAL);
    }

    public static boolean isStaffBuilding(Player player) {
        return player.getGameMode() == org.bukkit.GameMode.CREATIVE
                && player.hasPermission(de.raindancer.modules.ctf.util.PermissionNodes.ADMIN);
    }

    public void unlock() {
        LockedWorlds.unlock(settings.world());
        building.values().forEach(PasteJob::cancel);
        building.clear();
    }

    /** Where a map stands, if it has been built from the schematic as it is now. */
    public Optional<Built> builtNow(CtfMap map) {
        Built where = built.get(map.id());
        if (where == null) {
            return Optional.empty();
        }
        return version(map).filter(now -> now.equals(builtVersions.get(map.id()))).map(now -> where);
    }

    public boolean isBuilding(String id) {
        return building.containsKey(id);
    }

    private Optional<Long> version(CtfMap map) {
        Path file = maps.resolve(map.file()).normalize();
        try {
            return Optional.of(Files.getLastModifiedTime(file).toMillis() ^ Files.size(file));
        } catch (IOException missing) {
            return Optional.empty();
        }
    }

    /**
     * Builds a map into its slot (or rebuilds it, its schematic having changed). The schematic is read off the server
     * thread; the blocks go in chunk by chunk.
     *
     * @param done   told on the global thread where it stands
     * @param failed told a message key
     */
    public void build(CtfMap map, Consumer<Built> done, Consumer<String> failed) {
        Path file = maps.resolve(map.file()).normalize();
        if (!file.startsWith(maps.normalize()) || !Files.isRegularFile(file)) {
            failed.accept("ctf.admin.no-such-schematic");
            return;
        }
        if (building.containsKey(map.id())) {
            failed.accept("ctf.admin.already-building");
            return;
        }
        Scheduling.async(plugin, () -> {
            Schematic schematic;
            try {
                schematic = Schematic.read(file);
            } catch (IOException unreadable) {
                log.error("The map {} could not be read: {}", map.file(), unreadable.getMessage());
                Scheduling.global(plugin, () -> failed.accept("ctf.admin.schematic-unreadable"));
                return;
            }
            Scheduling.global(plugin, () -> {
                Optional<World> world = world();
                if (world.isEmpty()) {
                    failed.accept("ctf.admin.world-refused");
                    return;
                }
                Built where = built.getOrDefault(map.id(), new Built(map.id(), freeSlot() * SLOT_WIDTH, MAP_Y, 0));
                PasteJob job = new PasteJob(plugin, world.get(), schematic.placements(
                        new Spot(world.get().getName(), where.originX(), where.originY(), where.originZ())));
                building.put(map.id(), job);
                log.info("Building the map {} at x={}: {} chunk(s), {} unknown block state(s) left out.", map.id(),
                        where.originX(), job.chunks(), schematic.foreign().size());
                job.start(blocks -> {
                    building.remove(map.id());
                    built.put(map.id(), where);
                    version(map).ifPresent(version -> builtVersions.put(map.id(), version));
                    save();
                    log.info("The map {} is built: {} block(s).", map.id(), blocks);
                    done.accept(where);
                });
            });
        });
    }

    /** Forgets where a map stood, so the next round on it builds it again. */
    public void forget(String id) {
        built.remove(id);
        builtVersions.remove(id);
        save();
    }

    private int freeSlot() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Built each : built.values()) {
            used.add(Math.floorDiv(each.originX(), SLOT_WIDTH));
        }
        int slot = 0;
        while (used.contains(slot)) {
            slot++;
        }
        return slot;
    }

    /** Off the server thread: the file is a row in Core's database. */
    private void save() {
        List<Built> all = List.copyOf(built.values());
        Map<String, Long> versions = Map.copyOf(builtVersions);
        Scheduling.async(plugin, () -> builtFile.write(yaml -> all.forEach(where -> {
            String at = "maps." + where.id();
            yaml.set(at + ".x", where.originX());
            yaml.set(at + ".y", where.originY());
            yaml.set(at + ".z", where.originZ());
            yaml.set(at + ".version", versions.getOrDefault(where.id(), 0L));
        })));
    }

    /** A place in the arena world from a map spot, the map standing at {@code where}. */
    public Optional<Location> location(Built where, de.raindancer.modules.ctf.model.Point relative) {
        World world = Bukkit.getWorld(settings.world());
        if (world == null || relative == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, where.originX() + relative.x(), where.originY() + relative.y(),
                where.originZ() + relative.z(), relative.yaw(), relative.pitch()));
    }
}
