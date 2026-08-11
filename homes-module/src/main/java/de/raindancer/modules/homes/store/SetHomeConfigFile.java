package de.raindancer.modules.homes.store;

import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The settings a server that used to run the third-party {@code SetHome} plugin had chosen, read off
 * its {@code config.yml}.
 *
 * <h2>The shape, field for field</h2>
 * <pre>
 * cooldown: &lt;seconds&gt;
 * max-homes:
 *   default: &lt;int&gt;
 * cancel-on-move: &lt;bool&gt;
 * play-sound: &lt;bool&gt;
 * </pre>
 *
 * <p>{@code max-homes} could carry a key per permission group on SetHome — {@code vip}, {@code admin} —
 * but only {@code default} has anywhere to go: {@code HomeSettings.max} is the same kind of floor a
 * {@code homes.limit.<n>} permission already raises, so a per-group SetHome override becomes a
 * per-group permission an owner grants by hand rather than a setting this can carry across by itself.
 *
 * <p>{@code play-sound} lands on {@code HomeSettings.playSound()} — {@code HomeTravelService} plays
 * Core's own {@code Cues.TELEPORT} on arrival when it is on, the same enderman sound SetHome played,
 * so switching it off carries across exactly as switching off any other setting would.
 */
public final class SetHomeConfigFile {

    /** What SetHome's own file was always called, under its plugin's data folder. */
    public static final String FILE_NAME = "config.yml";

    /** What SetHome's {@code config.yml} held, all four fields with somewhere to go in {@code HomeSettings}. */
    public record Values(int cooldownSeconds, int maxHomes, boolean cancelOnMove, boolean playSound) {
    }

    private SetHomeConfigFile() {
    }

    /**
     * The settings the file holds, or empty when there is nothing to read.
     *
     * <p>Nothing here throws: a missing or unreadable file is a server that never ran SetHome, or one
     * whose file a hand edit broke, and either way the caller falls back to this module's own defaults
     * rather than failing to start.
     */
    public static Optional<Values> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
        return Optional.of(new Values(
                Math.max(0, yaml.getInt("cooldown", 0)),
                Math.max(0, yaml.getInt("max-homes.default", 15)),
                yaml.getBoolean("cancel-on-move", false),
                yaml.getBoolean("play-sound", true)));
    }

    /**
     * Where SetHome's settings actually are. See {@link SetHomeFiles#locate} for the search order.
     *
     * @param pluginsFolder  the server's {@code plugins} folder, or null when it could not be found
     * @param moduleDataFolder this module's own data folder
     * @return the first candidate that is an actual file, or empty when none of them are
     */
    public static Optional<Path> locate(Path pluginsFolder, Path moduleDataFolder) {
        return SetHomeFiles.locate(FILE_NAME, pluginsFolder, moduleDataFolder);
    }

    /**
     * Renames the file aside once it has been read, so this cannot run twice — same idiom as
     * {@code HomeCatalogue}'s migration, and for the same reason: kept, not deleted, in case this has
     * to be undone.
     */
    public static void setAside(Path file, LogChannel log) {
        SetHomeFiles.setAside(file, log);
    }
}
