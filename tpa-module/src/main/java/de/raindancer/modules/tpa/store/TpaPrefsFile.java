package de.raindancer.modules.tpa.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.tpa.model.TpaPrefs;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is accepting requests, and who has blocked whom.
 *
 * <h2>The shape on disk is the old plugin's, exactly</h2>
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     name: &lt;last seen&gt;
 *     accepting: &lt;bool&gt;
 *     blocked:
 *       - &lt;uuid&gt;
 *     blocked-names:
 *       &lt;uuid&gt;: &lt;name&gt;
 * </pre>
 * An upgrading server's {@code tpa.yml} is read as it stands, so nobody's block list quietly empties
 * on the day they update. {@code blocked-names} is written for whoever opens the file by hand — the
 * uuids alone are unreadable — and is never used for a decision.
 *
 * <h2>What is no longer here</h2>
 * The write-to-a-temporary-then-move dance and the private single-thread writer the old plugin had.
 * Both are {@link YamlStore}'s, which is where that code went after being written seven times.
 *
 * <p>A player who has decided nothing is not written at all. Otherwise a server keeps one entry per
 * person who has ever used the plugin, and every one of them says nothing.
 */
public final class TpaPrefsFile {

    /** What the file has always been called, under the plugin's own data folder. */
    public static final String FILE_NAME = "tpa.yml";

    private final YamlStore store;
    private final Map<UUID, TpaPrefs> prefs = new ConcurrentHashMap<>();
    /** Last-known names, for the file a person reads rather than for any decision. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /**
     * Whether the file on disk was readable.
     *
     * <p>False stops anything writing over it. A file that could not be parsed — one hand-edited
     * line, usually — is read as empty, and writing the empty result back would replace everybody's
     * block list with nothing. The one thing worse than not loading somebody's settings is deleting
     * them.
     */
    private volatile boolean readable = true;

    public TpaPrefsFile(Path file) {
        this.store = new YamlStore(file);
    }

    public Path file() {
        return store.file();
    }

    // ------------------------------------------------------------------------ reading

    /** Reads what is there. A file that is not there is a server nobody has decided anything on. */
    public void load() {
        prefs.clear();
        names.clear();
        readable = true;
        if (!store.exists()) {
            return;
        }
        ConfigurationSection players = store.read().getConfigurationSection("players");
        // A file that would not parse is reported by the store rather than thrown, and read as empty.
        // Empty and unreadable look identical from here, so the difference has to come from the store.
        if (!store.problems().isEmpty()) {
            readable = false;
            return;
        }
        if (players == null) {
            return;
        }
        for (String rawId : players.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(rawId);
            } catch (IllegalArgumentException notAnId) {
                // One hand-edited line must not cost everybody else their block list.
                continue;
            }
            ConfigurationSection theirs = players.getConfigurationSection(rawId);
            if (theirs == null) {
                continue;
            }
            String name = theirs.getString("name", "");
            if (!name.isBlank()) {
                names.put(who, name);
            }
            Set<UUID> blocked = new LinkedHashSet<>();
            for (String rawBlocked : theirs.getStringList("blocked")) {
                try {
                    blocked.add(UUID.fromString(rawBlocked));
                } catch (IllegalArgumentException notAnId) {
                    // Same again, one entry at a time.
                }
            }
            ConfigurationSection blockedNames = theirs.getConfigurationSection("blocked-names");
            if (blockedNames != null) {
                for (String rawId2 : blockedNames.getKeys(false)) {
                    try {
                        names.putIfAbsent(UUID.fromString(rawId2),
                                blockedNames.getString(rawId2, ""));
                    } catch (IllegalArgumentException notAnId) {
                        // Same again.
                    }
                }
            }
            TpaPrefs read = new TpaPrefs(theirs.getBoolean("accepting", true), blocked);
            if (read.isWorthKeeping()) {
                prefs.put(who, read);
            }
        }
    }

    /** What this player has decided, or the default if they never have. */
    public TpaPrefs of(UUID who) {
        return who == null ? TpaPrefs.untouched() : prefs.getOrDefault(who, TpaPrefs.untouched());
    }

    /** The name somebody last had, for a list that would otherwise be full of uuids. */
    public Optional<String> nameOf(UUID who) {
        return who == null ? Optional.empty()
                : Optional.ofNullable(names.get(who)).filter(name -> !name.isBlank());
    }

    /** How many players have decided anything, for the line in the log. */
    public int tracked() {
        return prefs.size();
    }

    // ------------------------------------------------------------------------ changing

    /**
     * Remembers what somebody has decided, and writes it out.
     *
     * <p>Written on every change rather than on a timer or at shutdown. These are decisions about who
     * may bother whom: one that is live now and gone after a restart is found by the person being
     * bothered again.
     */
    public void set(UUID who, String theirName, TpaPrefs decided) {
        if (who == null || decided == null) {
            return;
        }
        if (theirName != null && !theirName.isBlank()) {
            names.put(who, theirName);
        }
        if (decided.isWorthKeeping()) {
            prefs.put(who, decided);
        } else {
            prefs.remove(who);
        }
        save();
    }

    /** Notes the name somebody currently has, so a block list can be read by a person. */
    public void seen(UUID who, String theirName) {
        if (who != null && theirName != null && !theirName.isBlank()) {
            names.put(who, theirName);
        }
    }

    /**
     * Writes everything out, atomically. The temp-file-then-move is {@link YamlStore}'s.
     *
     * <p>Refuses outright when the file could not be read. Otherwise the first person to change
     * anything would write an in-memory map holding only their own entry over a file that still has
     * everybody else's — turning one unparseable line into every block list on the server.
     *
     * @return whether it was written
     */
    public boolean save() {
        if (!readable) {
            return false;
        }
        return store.write(yaml -> prefs.forEach((who, theirs) -> {
            String base = "players." + who;
            nameOf(who).ifPresent(name -> yaml.set(base + ".name", name));
            yaml.set(base + ".accepting", theirs.accepting());
            if (theirs.blocked().isEmpty()) {
                return;
            }
            yaml.set(base + ".blocked", theirs.blocked().stream().map(UUID::toString).toList());
            // Written for whoever opens this file by hand; never read for a decision.
            theirs.blocked().forEach(blocked ->
                    nameOf(blocked).ifPresent(name ->
                            yaml.set(base + ".blocked-names." + blocked, name)));
        }));
    }

    /**
     * Whether what is on disk could be read.
     *
     * <p>False means nothing will be written until somebody fixes the file — which is worth saying in
     * the log at start, because otherwise the symptom is settings that quietly never save.
     */
    public boolean isReadable() {
        return readable;
    }

    /** Anything the file could not be read as, for the diagnostic. */
    public java.util.List<String> problems() {
        return store.problems();
    }
}
