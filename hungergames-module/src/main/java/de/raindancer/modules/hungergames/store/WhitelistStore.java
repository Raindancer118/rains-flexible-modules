package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the old {@code whitelist.yml} — the one-time migration source, kept at the same path it always
 * had so an upgrading server's file is found without anybody having to move it by hand.
 *
 * <h2>Why this store only reads, and never writes</h2>
 * The tournament roster is not this file any more. It is {@code GameSession}'s, held as
 * {@code ParticipantData} and persisted through {@code YamlSessionStore} — see the note on
 * {@code player.WhitelistManager} in the source this was ported from: the previous plugin's own doubled
 * truth, dead-by-name here and eliminated-by-UUID in memory, was the bug this port removes. So this class
 * has exactly one job: turn the old name/team-number/dead-flag rows into a list a service can feed into
 * the session once, on first start after an upgrade. Whoever calls it decides *whether* to migrate — a
 * server already mid-tournament under the new format must never re-read an old file and re-add tributes
 * who were deliberately removed since — this store only decides whether the file can be read at all.
 *
 * <h2>Why names, not UUIDs, and why that is somebody else's problem</h2>
 * A v1 whitelist entry is a player's last-known name; resolving that into the UUID the new roster keys on
 * is a network call to Mojang, which needs a running server and does not belong anywhere a test has to
 * exercise it without one. So this store hands back names, and the resolution happens in whatever service
 * does the actual migration.
 */
public final class WhitelistStore {

    private static final LogChannel log = Log.of("hungergames");

    /** One row of the old whitelist: a name, a legacy team number ({@code 0} = none), and whether they were dead. */
    public record LegacyEntry(String name, int team, boolean dead) {
    }

    private final YamlStore store;
    private final List<String> problems = new ArrayList<>();

    public WhitelistStore(Path file) {
        this.store = new YamlStore(file);
    }

    /** What could not be read the last time {@link #readLegacy()} ran. Empty when it was clean. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * The old file's entries, or an empty list when there is nothing to migrate — no file, an empty
     * {@code players} list, or one that could not be parsed. A file this cannot read is quarantined so a
     * caller's "has this already been migrated" check, which typically keys off an empty result, does not
     * retry the same broken file forever.
     */
    public List<LegacyEntry> readLegacy() {
        synchronized (problems) {
            problems.clear();
        }
        if (!store.exists()) {
            return List.of();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            store.quarantine();
            return List.of();
        }
        List<?> rows = yaml.getList("players");
        if (rows == null) {
            return List.of();
        }
        List<LegacyEntry> entries = new ArrayList<>();
        for (Object row : rows) {
            readEntry(row).ifPresent(entries::add);
        }
        return entries;
    }

    private java.util.Optional<LegacyEntry> readEntry(Object row) {
        if (row instanceof Map<?, ?> map) {
            Object rawName = map.get("name");
            if (rawName == null || String.valueOf(rawName).isBlank()) {
                note("an entry without a name was skipped");
                return java.util.Optional.empty();
            }
            int team = map.get("team") instanceof Number number ? number.intValue() : 0;
            boolean dead = Boolean.TRUE.equals(map.get("dead"));
            return java.util.Optional.of(new LegacyEntry(String.valueOf(rawName), team, dead));
        }
        if (row instanceof String name && !name.isBlank()) {
            return java.util.Optional.of(new LegacyEntry(name, 0, false));
        }
        note("an unreadable entry was skipped (" + row + ")");
        return java.util.Optional.empty();
    }

    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (problems) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (problems) {
            problems.add(problem);
        }
        log.warn("whitelist.yml: {}", problem);
    }
}
