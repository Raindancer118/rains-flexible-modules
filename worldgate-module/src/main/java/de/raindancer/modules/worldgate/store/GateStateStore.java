package de.raindancer.modules.worldgate.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.worldgate.model.Dimension;
import de.raindancer.modules.worldgate.model.GateState;
import de.raindancer.modules.worldgate.model.GateStates;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Whether the Nether and the End are open, on disk.
 *
 * <h2>Why this is not a {@code SettingsStore}</h2>
 * A lock is something an admin flips at short notice — winding a dimension down before a wipe, or
 * shutting it entirely — not something written once when the server is set up. That is exactly the
 * distinction {@code RtpLocationStorage} draws against {@code RtpSettings}: an owner's preference goes
 * through {@code context.settings(...)}, live admin-toggled state gets its own small file on top of
 * {@link YamlStore}.
 *
 * <h2>One file, two lines</h2>
 * There is no list here, so this reads and writes the whole thing at once rather than keying entries
 * by id the way {@code RtpLocationStorage} does for a pool of many spots.
 */
public final class GateStateStore {

    private static final LogChannel log = Log.of("worldgate");

    private final YamlStore store;

    public GateStateStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("worldgate.yml"));
    }

    /** Where this is kept, for a diagnostic and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    /**
     * Both dimensions' state.
     *
     * <p>A missing or unreadable file is a fresh install: both open. A dimension whose value will not
     * parse is reset to open on its own, named in the log — one mangled line must not cost the server
     * the other dimension's lock.
     */
    public GateStates load() {
        YamlConfiguration yaml = store.read();
        GateState nether = read(yaml.getString("nether"), Dimension.NETHER);
        GateState end = read(yaml.getString("end"), Dimension.END);
        return new GateStates(nether, end);
    }

    /** Writes both. @return whether it reached the disk */
    public boolean save(GateStates states) {
        if (states == null) {
            return false;
        }
        return store.write(yaml -> {
            yaml.set("nether", states.nether().name());
            yaml.set("end", states.end().name());
        });
    }

    private static GateState read(String text, Dimension dimension) {
        if (text == null || text.isBlank()) {
            return GateState.OPEN;
        }
        try {
            return GateState.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAState) {
            log.warn("'{}' is not a state for {}, so it is treated as open.", text, dimension.label());
            return GateState.OPEN;
        }
    }
}
