package de.raindancer.modules.homes.store;

import de.raindancer.modules.homes.rules.HomeNameRule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The homes a server that used to run the third-party {@code SetHome} plugin already has, read off its
 * {@code homes.yml}.
 *
 * <h2>Why this is a second reader, not a change to {@link LegacyHomesFile}</h2>
 * The two files are both called {@code homes.yml} and both hold homes, and that is where the resemblance
 * ends. {@link LegacyHomesFile} reads what this module's own predecessor, {@code RainsHomes}, used to
 * write — a {@code players:} section with a last-known name and a created timestamp per home. SetHome
 * never had either: its file is the player id straight at the top, holding home names straight at
 * coordinates. Folding the two shapes into one reader would mean the first one wrong about a field
 * decides silently which file it thinks it is looking at; two small readers each make one honest claim
 * about the shape they were written against.
 *
 * <h2>The shape, field for field</h2>
 * <pre>
 * &lt;uuid&gt;:
 *   &lt;name&gt;:
 *     world: &lt;name&gt;
 *     x: &lt;double&gt;   y: &lt;double&gt;   z: &lt;double&gt;
 *     yaw: &lt;double&gt; pitch: &lt;double&gt;
 * </pre>
 *
 * <p>Nothing about who owned the name last, nothing about when a home was made, nothing about an icon —
 * SetHome never stored any of that, so {@link LegacyHomesFile.Entry#ownerName()},
 * {@link LegacyHomesFile.Entry#icon()} and {@link LegacyHomesFile.Entry#createdAt()} come back blank and
 * zero for every entry this reads. That is not data lost in translation; it is data that was never there.
 */
public final class SetHomePluginFile {

    /** What SetHome's own file was always called, under its plugin's data folder. */
    public static final String FILE_NAME = "homes.yml";

    private static final HomeNameRule NAMES = new HomeNameRule();

    private SetHomePluginFile() {
    }

    /**
     * Everything the file holds.
     *
     * <p>Nothing here throws, for the same reason {@link LegacyHomesFile#read} does not: a missing file
     * is a server that never ran SetHome, and one hand-edited or half-written entry must not cost every
     * other player's homes.
     */
    public static List<LegacyHomesFile.Entry> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (Exception unreadable) {
            return List.of();
        }

        List<LegacyHomesFile.Entry> found = new ArrayList<>();
        for (String rawId : yaml.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(rawId);
            } catch (IllegalArgumentException notAnId) {
                continue;
            }
            ConfigurationSection homes = yaml.getConfigurationSection(rawId);
            if (homes == null) {
                continue;
            }
            for (String rawName : homes.getKeys(false)) {
                ConfigurationSection where = homes.getConfigurationSection(rawName);
                // Folded on the way in, same as LegacyHomesFile — so a name SetHome let somebody type
                // with capitals is reachable through this module under the lower-case key it always
                // would have collapsed onto.
                String name = NAMES.normalise(rawName);
                if (where == null || name == null) {
                    continue;
                }
                String world = where.getString("world", "");
                if (world.isBlank()) {
                    continue;
                }
                found.add(new LegacyHomesFile.Entry(owner, "", name, world,
                        where.getDouble("x"), where.getDouble("y"), where.getDouble("z"),
                        (float) where.getDouble("yaw"), (float) where.getDouble("pitch"),
                        0L, ""));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Where SetHome's export actually is, tried in the order a mislaid one is most likely found.
     *
     * <p>The expected place is {@code <plugins>/SetHome/homes.yml} — a sibling of every plugin's data
     * folder, this module's included. But "expected" is not "guaranteed": the folder can be renamed,
     * cased differently by whatever unzipped it, or an admin migrating by hand can drop the export
     * beside this module's own files instead of hunting for where SetHome used to live. None of that
     * should mean 144 homes silently do not come back — so this tries, in order:
     *
     * <ol>
     *     <li>{@code <plugins>/SetHome/homes.yml} — where the plugin itself always put it;</li>
     *     <li>any folder directly under {@code <plugins>} whose name is {@code SetHome} ignoring case,
     *         holding a {@code homes.yml} — the folder survived, only its casing did not;</li>
     *     <li>{@code <module data>/SetHome/homes.yml} — the export copied in next to this module's own
     *         files rather than reconstructed under {@code <plugins>};</li>
     *     <li>{@code <module data>/sethome-homes.yml} — the file itself, renamed and dropped straight
     *         into this module's data folder, for an admin doing the migration by hand.</li>
     * </ol>
     *
     * @param pluginsFolder  the server's {@code plugins} folder, or null when it could not be found
     * @param moduleDataFolder this module's own data folder
     * @return the first candidate that is an actual file, or empty when none of them are
     */
    public static Optional<Path> locate(Path pluginsFolder, Path moduleDataFolder) {
        List<Path> candidates = new ArrayList<>();
        if (pluginsFolder != null) {
            candidates.add(pluginsFolder.resolve("SetHome").resolve(FILE_NAME));
        }
        if (moduleDataFolder != null) {
            candidates.add(moduleDataFolder.resolve("SetHome").resolve(FILE_NAME));
            candidates.add(moduleDataFolder.resolve("sethome-" + FILE_NAME));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return findCaseInsensitive(pluginsFolder);
    }

    /**
     * A folder called {@code SetHome} in every case but the right one, still under {@code plugins}.
     *
     * <p>Whatever unzipped an export onto a fresh server is exactly the kind of step that lower-cases a
     * folder name without anybody deciding it should. One directory listing settles it either way, and
     * costs nothing on every other boot once the file has been set aside.
     */
    private static Optional<Path> findCaseInsensitive(Path pluginsFolder) {
        if (pluginsFolder == null || !Files.isDirectory(pluginsFolder)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(pluginsFolder)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString()
                            .equalsIgnoreCase("SetHome"))
                    .map(dir -> dir.resolve(FILE_NAME))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }
}
