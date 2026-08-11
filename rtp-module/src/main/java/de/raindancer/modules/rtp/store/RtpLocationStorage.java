package de.raindancer.modules.rtp.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.rtp.model.PreparedSpot;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The prepared spots, on disk.
 *
 * <h2>One file, written whole</h2>
 * The same shape as the report queue's: at most a few thousand small entries, changing in bursts —
 * a daily top-up, a player's own trip marking one used — rather than continuously, and read as a whole
 * once at startup. A file per spot would be a directory of thousands of five-line files.
 *
 * <h2>The write itself is Core's</h2>
 * {@link YamlStore} owns the write-to-a-temporary-then-move dance, so a server killed mid-save has
 * either the old file or the new one and never half of each.
 */
public final class RtpLocationStorage {

    private static final LogChannel log = Log.of("rtp");

    /** The file this version writes. Branch on it rather than guessing when the shape changes. */
    public static final int DATA_VERSION = 1;

    private final YamlStore store;

    public RtpLocationStorage(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("rtp-locations.yml"));
    }

    /** Where they are kept, for a diagnostic and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    /**
     * Everything on disk.
     *
     * <p>An entry that will not read is skipped and named, and the rest still load. One spot with a
     * mangled world must not cost the server every other one already prepared.
     */
    public List<PreparedSpot> load() {
        ConfigurationSection root = store.read().getConfigurationSection("locations");
        List<PreparedSpot> spots = new ArrayList<>();
        if (root == null) {
            return spots;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) {
                unreadable.add(id);
                continue;
            }
            try {
                spots.add(read(id, entry));
            } catch (RuntimeException broken) {
                unreadable.add(id);
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} prepared location(s) could not be read and have been skipped: {}. The file "
                    + "was left untouched.", unreadable.size(), String.join(", ", unreadable));
        }
        return spots;
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean saveAll(Collection<PreparedSpot> spots) {
        return store.write(yaml -> {
            yaml.set("version", DATA_VERSION);
            if (spots == null) {
                return;
            }
            for (PreparedSpot spot : spots) {
                String at = "locations." + spot.id();
                yaml.set(at + ".world", spot.world());
                yaml.set(at + ".x", spot.x());
                yaml.set(at + ".y", spot.y());
                yaml.set(at + ".z", spot.z());
                yaml.set(at + ".prepared-at", spot.preparedAt().toString());
                yaml.set(at + ".used-by", spot.usedBy().stream().map(UUID::toString).toList());
            }
        });
    }

    private static PreparedSpot read(String id, ConfigurationSection entry) {
        return new PreparedSpot(id, requiredWorld(entry.getString("world")),
                entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                requiredInstant(entry.getString("prepared-at")),
                usedBy(entry.getStringList("used-by")));
    }

    private static String requiredWorld(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a prepared location with no world");
        }
        return text;
    }

    private static Instant requiredInstant(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a prepared location with no time on it");
        }
        return Instant.parse(text);
    }

    private static Set<UUID> usedBy(List<String> raw) {
        Set<UUID> players = new LinkedHashSet<>();
        for (String text : raw) {
            try {
                players.add(UUID.fromString(text));
            } catch (IllegalArgumentException notAUuid) {
                // Skipped rather than failing the whole entry — one mangled player in the used-by
                // list is not a reason to throw away a perfectly good location.
            }
        }
        return players;
    }
}
