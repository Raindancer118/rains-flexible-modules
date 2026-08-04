package de.raindancer.modules.homes.store;

import de.raindancer.modules.homes.rules.HomeNameRule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The homes an upgrading server already has, read off the old {@code homes.yml}.
 *
 * <h2>Why this exists at all</h2>
 * Because a home is a place, and places are RainsCore's — which means the homes on an upgrading
 * server are in the wrong file. Everything about that is fine except the one thing that is not: if
 * nothing reads the old file, the plugin starts, the list is empty, and the file with everybody's
 * homes in it is still sitting on disk looking perfectly correct. Nobody would report that as a
 * migration bug; they would report that homes were deleted.
 *
 * <h2>What it does not do</h2>
 * It does not write, delete or move anything. It reads, and the caller decides — see
 * {@link HomeCatalogue#importLegacy}. That split is deliberate: the reading is the part with a fixture
 * behind it, and it can be run against a real server's file without touching it.
 *
 * <h2>The shape, field for field</h2>
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     name: &lt;last known player name&gt;
 *     homes:
 *       &lt;name&gt;:
 *         world: &lt;name&gt;
 *         x: &lt;double&gt;   y: &lt;double&gt;   z: &lt;double&gt;
 *         yaw: &lt;double&gt; pitch: &lt;double&gt;      # doubles on disk, floats in code
 *         created: &lt;epoch millis&gt;
 *         icon: &lt;material name&gt;               # only when one was chosen
 * </pre>
 *
 * <p>{@code icon} being absent is the common case, not a broken entry: the old writer omitted the key
 * rather than writing a blank, so most homes on a real server have no icon line at all.
 */
public final class LegacyHomesFile {

    /** What the file was always called, under the plugin's own data folder. */
    public static final String FILE_NAME = "homes.yml";

    /** One home as the old file held it. */
    public record Entry(UUID owner, String ownerName, String name, String world,
                        double x, double y, double z, float yaw, float pitch,
                        long createdAt, String icon) {
    }

    private static final HomeNameRule NAMES = new HomeNameRule();

    private LegacyHomesFile() {
    }

    /**
     * Everything the file holds.
     *
     * <p>Nothing here throws. A missing file is a server that never had the old plugin; a file that
     * does not parse, or an entry that does not make sense, is one hand-edited line — and failing the
     * whole import over it would lose every other home on the server. The old loader took the same
     * view, and counted what it skipped.
     */
    public static List<Entry> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (Exception unreadable) {
            return List.of();
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return List.of();
        }

        List<Entry> found = new ArrayList<>();
        for (String rawId : players.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(rawId);
            } catch (IllegalArgumentException notAnId) {
                continue;
            }
            ConfigurationSection theirs = players.getConfigurationSection(rawId);
            if (theirs == null) {
                continue;
            }
            String ownerName = theirs.getString("name", "");
            ConfigurationSection homes = theirs.getConfigurationSection("homes");
            if (homes == null) {
                continue;
            }
            for (String rawName : homes.getKeys(false)) {
                ConfigurationSection where = homes.getConfigurationSection(rawName);
                // Normalised on the way in, exactly as the old loader did — so a key somebody
                // capitalised by hand has always been reachable as lower case.
                String name = NAMES.normalise(rawName);
                if (where == null || name == null) {
                    continue;
                }
                String world = where.getString("world", "");
                if (world.isBlank()) {
                    // A home with no world is nowhere. Importing it would put an unreachable entry
                    // in the store that nothing can ever fix.
                    continue;
                }
                found.add(new Entry(owner, ownerName, name, world,
                        where.getDouble("x"), where.getDouble("y"), where.getDouble("z"),
                        // Doubles on disk although the record held floats. Read back the same way
                        // round, or every imported home faces slightly the wrong direction.
                        (float) where.getDouble("yaw"), (float) where.getDouble("pitch"),
                        where.getLong("created"),
                        where.getString("icon", "")));
            }
        }
        return List.copyOf(found);
    }
}
