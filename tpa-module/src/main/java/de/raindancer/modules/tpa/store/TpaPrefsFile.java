package de.raindancer.modules.tpa.store;

import de.raindancer.core.data.store.KeyedDocument;
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
 * <h2>On a network</h2>
 * One player per key ({@link KeyedDocument}): a change starts from what the database holds for them and writes only
 * their entry; another server's change is read in; a file that cannot be read is never written over.
 *
 * <p>A player who has decided nothing is not written at all. Otherwise a server keeps one entry per
 * person who has ever used the plugin, and every one of them says nothing.
 */
public final class TpaPrefsFile {

    /** What the file has always been called, under the plugin's own data folder. */
    public static final String FILE_NAME = "tpa.yml";

    private final YamlStore store;
    private final KeyedDocument<TpaPrefs> document;
    /** Last-known names, for the file a person reads rather than for any decision. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public TpaPrefsFile(Path file) {
        this(YamlStore.network(file));
    }

    /** On a store of the caller's choosing — a network document in a test. */
    public TpaPrefsFile(YamlStore store) {
        this.store = store;
        this.document = new KeyedDocument<>(store, "players", this::read, this::write);
    }

    public Path file() {
        return store.file();
    }

    // ------------------------------------------------------------------------ reading

    /**
     * Reads what is there. What could not be read — one hand-edited line, usually — leaves what is known as it is,
     * and stops anything being written until it can: writing back an empty reading would delete every block list.
     */
    public void load() {
        document.load();
    }

    /** One player's entry as stored, null when it says nothing; their names noted on the way. */
    private TpaPrefs read(ConfigurationSection players, String key) {
        UUID who;
        try {
            who = UUID.fromString(key);
        } catch (IllegalArgumentException notAnId) {
            // One hand-edited line must not cost everybody else their block list.
            return null;
        }
        ConfigurationSection theirs = players.getConfigurationSection(key);
        if (theirs == null) {
            return null;
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
            for (String rawId : blockedNames.getKeys(false)) {
                try {
                    names.putIfAbsent(UUID.fromString(rawId), blockedNames.getString(rawId, ""));
                } catch (IllegalArgumentException notAnId) {
                    // Same again.
                }
            }
        }
        TpaPrefs read = new TpaPrefs(theirs.getBoolean("accepting", true), blocked,
                theirs.getBoolean("auto-accept", false));
        return read.isWorthKeeping() ? read : null;
    }

    /** What this player has decided, or the default if they never have. */
    public TpaPrefs of(UUID who) {
        return who == null ? TpaPrefs.untouched() : document.get(who.toString()).orElseGet(TpaPrefs::untouched);
    }

    /** The name somebody last had, for a list that would otherwise be full of uuids. */
    public Optional<String> nameOf(UUID who) {
        return who == null ? Optional.empty()
                : Optional.ofNullable(names.get(who)).filter(name -> !name.isBlank());
    }

    /** How many players have decided anything, for the line in the log. */
    public int tracked() {
        return document.all().size();
    }

    // ------------------------------------------------------------------------ changing

    /**
     * Remembers what somebody has decided, and writes it out — on every change: these are decisions about who may
     * bother whom, and one gone after a restart is found by the person being bothered again.
     */
    public void set(UUID who, String theirName, TpaPrefs decided) {
        change(who, theirName, ignored -> decided);
    }

    /**
     * Changes what somebody has decided, starting from what the database holds for them — not from this server's
     * copy, which another server may have changed a moment ago — and writes only their entry.
     *
     * @return whether it was written
     */
    public boolean change(UUID who, String theirName, java.util.function.UnaryOperator<TpaPrefs> change) {
        if (who == null || change == null) {
            return false;
        }
        seen(who, theirName);
        return document.change(who.toString(), now -> {
            TpaPrefs decided = change.apply(now == null ? TpaPrefs.untouched() : now);
            return decided.isWorthKeeping() ? decided : null;
        });
    }

    /** Notes the name somebody currently has, so a block list can be read by a person. */
    public void seen(UUID who, String theirName) {
        if (who != null && theirName != null && !theirName.isBlank()) {
            names.put(who, theirName);
        }
    }

    private void write(ConfigurationSection yaml, String base, TpaPrefs theirs) {
        UUID who = UUID.fromString(base.substring(base.lastIndexOf('.') + 1));
        nameOf(who).ifPresent(name -> yaml.set(base + ".name", name));
        yaml.set(base + ".accepting", theirs.accepting());
        if (theirs.autoAccept()) {
            yaml.set(base + ".auto-accept", true);
        }
        if (theirs.blocked().isEmpty()) {
            return;
        }
        yaml.set(base + ".blocked", theirs.blocked().stream().map(UUID::toString).toList());
        // Written for whoever opens this by hand; never read for a decision.
        theirs.blocked().forEach(blocked ->
                nameOf(blocked).ifPresent(name -> yaml.set(base + ".blocked-names." + blocked, name)));
    }

    /**
     * Whether what is on disk could be read. False means nothing will be written until somebody fixes the file —
     * worth saying in the log at start, because otherwise the symptom is settings that quietly never save.
     */
    public boolean isReadable() {
        return document.readable();
    }

    /** Anything the file could not be read as, for the diagnostic. */
    public java.util.List<String> problems() {
        return store.problems();
    }
}
