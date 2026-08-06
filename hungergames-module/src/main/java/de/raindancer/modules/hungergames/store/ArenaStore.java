package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.model.ArenaRing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where the arena is, written down so that a restart does not lose it.
 *
 * <h2>The failure this exists to prevent</h2>
 * In the plugin this is ported from, the arena's centre, its platform positions and its underground floor
 * lived in a static {@code GameState} singleton and nowhere else. A server restarted between {@code /init}
 * and {@code /start} — which is a completely ordinary thing to do, since {@code /init} is run the afternoon
 * before and rebuilding an arena takes minutes — came back with the phase restored from {@code session.yml}
 * and no idea where anything was. {@code /startup} then found an empty platform list and put the round back
 * in the lobby, and the only way forward was to run {@code /init} again over the arena that was already
 * standing.
 *
 * <p>So this is written once, at the end of {@code /init}, and read once, when the module starts. It is not
 * touched again during a round: the arena does not move, and a store rewritten on every tick is a store that
 * can be corrupted by a crash at any moment rather than only during the one second it is being built.
 *
 * <h2>Why the layout is stored and not recomputed</h2>
 * {@link ArenaLayout#of} is a pure function, so recomputing it from the centre and the player count would
 * give the same answer — right up until somebody edits {@code arena.platform-min-gap} between the round
 * being built and the round being run. Then the recomputed platforms are somewhere the actual, pasted
 * platforms are not, and forty tributes levitate up into solid ground. What was built is a fact about the
 * world; it is stored as one.
 *
 * <p>No Bukkit server type is imported here, in keeping with the rest of this package: {@link ArenaLayout}
 * names its world as a string, which is exactly what makes it storable and testable without a server.
 */
public final class ArenaStore {

    private static final LogChannel log = Log.of("hungergames");

    private final YamlStore store;

    public ArenaStore(Path file) {
        this.store = new YamlStore(file);
    }

    /** What was wrong with the file the last time {@link #load()} refused it. */
    public List<String> problems() {
        return store.problems();
    }

    /**
     * The arena as it was last built, or empty when there is not one.
     *
     * <p>Empty rather than a partially-read layout: a platform list that lost half its entries to a truncated
     * file would put half the tributes in the right place and the other half nowhere. Anything that does not
     * read back completely is treated as no arena at all, which is the state {@code /init} knows how to fix.
     */
    public Optional<ArenaLayout> load() {
        if (!store.exists()) {
            return Optional.empty();
        }
        YamlConfiguration yaml = store.read();
        ConfigurationSection arena = yaml.getConfigurationSection("arena");
        if (arena == null) {
            return Optional.empty();
        }
        String world = arena.getString("world");
        if (world == null || world.isBlank()) {
            log.warn("The stored arena names no world, so it was ignored — run /init again.");
            return Optional.empty();
        }

        List<ArenaLayout.Stand> platforms = stands(arena.getConfigurationSection("platforms"));
        List<ArenaLayout.Stand> underground = stands(arena.getConfigurationSection("underground"));
        if (platforms.isEmpty() || platforms.size() != underground.size()) {
            log.warn("The stored arena has {} platform(s) and {} underground start(s), which cannot both be "
                            + "right — it was ignored, so run /init again.",
                    platforms.size(), underground.size());
            return Optional.empty();
        }

        ArenaRing ring = new ArenaRing(
                platforms.size(),
                arena.getDouble("ring.radius"),
                arena.getInt("ring.platform-width"),
                arena.getInt("ring.minimum-gap"));

        return Optional.of(new ArenaLayout(
                world,
                arena.getInt("centre.x"), arena.getInt("centre.y"), arena.getInt("centre.z"),
                ring, platforms, underground,
                arena.getInt("terrain-radius"),
                arena.getInt("room.floor-y"), arena.getInt("room.ceiling-y"), arena.getInt("room.radius"),
                arena.getInt("lobby.x"), arena.getInt("lobby.y"), arena.getInt("lobby.z"),
                arena.getInt("lobby.width"), arena.getInt("lobby.depth"), arena.getInt("lobby.height")));
    }

    /** Writes the arena down. Returns whether it reached the disk. */
    public boolean save(ArenaLayout layout) {
        return store.write(yaml -> {
            yaml.set("arena.world", layout.world());
            yaml.set("arena.centre.x", layout.centreX());
            yaml.set("arena.centre.y", layout.centreY());
            yaml.set("arena.centre.z", layout.centreZ());

            yaml.set("arena.ring.radius", layout.ring().radius());
            yaml.set("arena.ring.platform-width", layout.ring().platformWidth());
            yaml.set("arena.ring.minimum-gap", layout.ring().minimumGap());

            yaml.set("arena.terrain-radius", layout.terrainRadius());
            yaml.set("arena.room.floor-y", layout.roomFloorY());
            yaml.set("arena.room.ceiling-y", layout.roomCeilingY());
            yaml.set("arena.room.radius", layout.roomRadius());

            yaml.set("arena.lobby.x", layout.lobbyBaseX());
            yaml.set("arena.lobby.y", layout.lobbyBaseY());
            yaml.set("arena.lobby.z", layout.lobbyBaseZ());
            yaml.set("arena.lobby.width", layout.lobbyWidth());
            yaml.set("arena.lobby.depth", layout.lobbyDepth());
            yaml.set("arena.lobby.height", layout.lobbyHeight());

            writeStands(yaml, "arena.platforms", layout.platforms());
            writeStands(yaml, "arena.underground", layout.undergroundStarts());
        });
    }

    /** Forgets the arena — for a server that is starting a genuinely new tournament. */
    public boolean clear() {
        return store.write(yaml -> yaml.set("arena", null));
    }

    private static void writeStands(YamlConfiguration yaml, String path, List<ArenaLayout.Stand> stands) {
        for (int i = 0; i < stands.size(); i++) {
            ArenaLayout.Stand stand = stands.get(i);
            // Indexed by position rather than written as a list of maps: a list of maps round-trips through
            // SnakeYAML as a List<Map<String, Object>> whose numeric types depend on how the file was
            // written, and a double that comes back as an Integer is a platform half a block off.
            yaml.set(path + "." + i + ".x", stand.x());
            yaml.set(path + "." + i + ".y", stand.y());
            yaml.set(path + "." + i + ".z", stand.z());
            yaml.set(path + "." + i + ".yaw", (double) stand.yaw());
        }
    }

    private static List<ArenaLayout.Stand> stands(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }
        List<ArenaLayout.Stand> found = new ArrayList<>();
        // In numeric order, not the file's order. The index *is* the tribute's slot on the ring, so a file
        // whose keys came back as 0, 1, 10, 11, 2 would silently reshuffle who stands where.
        List<Integer> indices = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                indices.add(Integer.parseInt(key));
            } catch (NumberFormatException notAnIndex) {
                log.warn("The stored arena has a platform key '{}' that is not a number — it was skipped.",
                        key);
            }
        }
        indices.sort(Integer::compareTo);
        for (int index : indices) {
            ConfigurationSection one = section.getConfigurationSection(String.valueOf(index));
            if (one == null) {
                continue;
            }
            found.add(new ArenaLayout.Stand(one.getDouble("x"), one.getDouble("y"), one.getDouble("z"),
                    (float) one.getDouble("yaw")));
        }
        return found;
    }
}
