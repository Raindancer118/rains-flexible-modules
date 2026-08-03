package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Things somebody has to be told, kept until they are there to be told.
 *
 * <h2>What this replaces</h2>
 * Two places that quietly gave up. A mute told the player why — <em>if they happened to be online</em>;
 * a closed report told the reporter what was decided — <em>if they happened to be online</em>. Both are
 * exactly the cases where they usually are not: somebody muted for spam often logs off in a huff, and a
 * report is dealt with an hour after it was filed.
 *
 * <p>Dropping the line is the worst of the options. The player concludes nothing happened — that the
 * mute is a bug, that their report was ignored — and that conclusion is what a support channel spends
 * its evenings on. Delivering it late is strange for nobody: "while you were away" is a sentence people
 * already understand.
 *
 * <h2>Why the message key is stored rather than the message</h2>
 * Because the wording belongs to the owner's {@code messages.yml}, and a line rendered in March and
 * delivered in April should read the way the file reads in April. Storing the rendered component would
 * also make every stored notice a copy of text somebody may have since fixed a typo in.
 *
 * <h2>Thread safety</h2>
 * Written from the punishment and report services, which are reached from chat events; read from the
 * join handler. Hence the concurrent map and the copy-on-write lists.
 */
public final class PendingNotices {

    private static final LogChannel log = Log.of("moderation");

    /**
     * How many are kept for one player.
     *
     * <p>A ceiling because the alternative is unbounded: a player nobody can reach plus a moderator
     * with a macro would grow this file for ever. The oldest go first — the newest news is the news
     * worth having, and "you were muted twelve mutes ago" helps nobody.
     */
    public static final int MOST_PER_PLAYER = 20;

    /**
     * One thing to say.
     *
     * @param key    a message key, looked up when it is finally sent
     * @param values the placeholders that line needs
     */
    public record Notice(String key, Map<String, String> values) {

        public Notice {
            values = Map.copyOf(values == null ? Map.of() : values);
        }

        /** The values as {@code Messages.send} wants them: key, value, key, value. */
        public Object[] asArguments() {
            List<Object> flat = new ArrayList<>();
            values.forEach((name, value) -> {
                flat.add(name);
                flat.add(value);
            });
            return flat.toArray();
        }
    }

    private final Map<UUID, CopyOnWriteArrayList<Notice>> waiting = new ConcurrentHashMap<>();
    private final YamlStore store;

    public PendingNotices(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("pending.yml"));
    }

    /** Keeps one for somebody who is not here to be told. */
    public void keep(UUID who, String key, Map<String, String> values) {
        if (who == null || key == null || key.isBlank()) {
            return;
        }
        CopyOnWriteArrayList<Notice> theirs =
                waiting.computeIfAbsent(who, id -> new CopyOnWriteArrayList<>());
        theirs.add(new Notice(key.trim(), values));
        while (theirs.size() > MOST_PER_PLAYER) {
            theirs.removeFirst();
        }
    }

    /**
     * Everything waiting for them, and forgets it in the same step.
     *
     * <p>One call rather than a read and a clear, so two joins in quick succession cannot both deliver
     * the same notice — and so nobody can forget the second half and tell a player the same thing on
     * every login for the rest of the year.
     */
    public List<Notice> forgetAndTake(UUID who) {
        if (who == null) {
            return List.of();
        }
        // remove() rather than get()-then-clear(): it also drops the entry, so the map does not grow
        // by one per player who was ever offline at the wrong moment.
        CopyOnWriteArrayList<Notice> theirs = waiting.remove(who);
        return theirs == null ? List.of() : List.copyOf(theirs);
    }

    /** How many players have something waiting. */
    public int size() {
        return waiting.size();
    }

    /** Reads what is on disk. Called once, when the module starts. */
    public void load() {
        waiting.clear();
        ConfigurationSection root = store.read().getConfigurationSection("waiting");
        if (root == null) {
            return;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
                continue;
            }
            for (Map<?, ?> entry : root.getMapList(id)) {
                Object key = entry.get("key");
                if (key == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                Object stored = entry.get("values");
                if (stored instanceof Map<?, ?> pairs) {
                    pairs.forEach((name, value) -> {
                        if (name != null && value != null) {
                            values.put(name.toString(), value.toString());
                        }
                    });
                }
                keep(who, key.toString(), values);
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in pending.yml are not player ids and have been skipped: {}.",
                    unreadable.size(), String.join(", ", unreadable));
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> waiting.forEach((who, theirs) -> {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Notice notice : theirs) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", notice.key());
                entry.put("values", new LinkedHashMap<>(notice.values()));
                entries.add(entry);
            }
            yaml.set("waiting." + who, entries);
        }));
    }
}
