package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

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

    /** One change this server made and has not written yet: a notice kept, or one that was delivered. */
    private record Change(UUID who, boolean kept, Notice notice) {
    }

    private final Map<UUID, CopyOnWriteArrayList<Notice>> waiting = new ConcurrentHashMap<>();
    private final List<Change> unwritten = new ArrayList<>();
    private final YamlStore store;
    private final NetworkBus.Subscription following;

    /** The same notices on every server: a mute handed out on one is told on whichever the player logs into. */
    public PendingNotices(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("pending.yml")));
    }

    PendingNotices(YamlStore store) {
        this.store = store;
        this.following = store.onChange(this::reload);
    }

    public void close() {
        following.close();
    }

    /** Keeps one for somebody who is not here to be told. */
    public synchronized void keep(UUID who, String key, Map<String, String> values) {
        if (who == null || key == null || key.isBlank()) {
            return;
        }
        Notice notice = new Notice(key.trim(), values);
        append(who, notice);
        unwritten.add(new Change(who, true, notice));
    }

    private void append(UUID who, Notice notice) {
        CopyOnWriteArrayList<Notice> theirs =
                waiting.computeIfAbsent(who, id -> new CopyOnWriteArrayList<>());
        theirs.add(notice);
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
    public synchronized List<Notice> forgetAndTake(UUID who) {
        if (who == null) {
            return List.of();
        }
        // remove() rather than get()-then-clear(): it also drops the entry, so the map does not grow
        // by one per player who was ever offline at the wrong moment.
        CopyOnWriteArrayList<Notice> theirs = waiting.remove(who);
        if (theirs == null) {
            return List.of();
        }
        List<Notice> taken = List.copyOf(theirs);
        // Those notices only: one another server kept meanwhile is not this call's to forget.
        taken.forEach(notice -> unwritten.add(new Change(who, false, notice)));
        return taken;
    }

    /** How many players have something waiting. */
    public int size() {
        return waiting.size();
    }

    /**
     * Reads what is stored, once when the module starts. Unreadable leaves what is held as it is: empty
     * would be every notice forgotten.
     */
    public synchronized void load() {
        store.readIfReadable().ifPresent(this::adopt);
    }

    /** Another server kept or delivered something: this server's changes go out first, then theirs come in. */
    private synchronized void reload() {
        if (flush()) {
            load();
        }
    }

    private void adopt(YamlConfiguration yaml) {
        Map<UUID, List<Notice>> read = new java.util.HashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("waiting");
        List<String> unreadable = new ArrayList<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                UUID who;
                try {
                    who = UUID.fromString(id);
                } catch (IllegalArgumentException notAnId) {
                    unreadable.add(id);
                    continue;
                }
                read.put(who, noticesOf(root.getMapList(id)));
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in pending.yml are not player ids and have been skipped: {}.",
                    unreadable.size(), String.join(", ", unreadable));
        }
        waiting.clear();
        read.forEach((who, notices) -> notices.forEach(notice -> append(who, notice)));
        // Not written yet, so not in what was read: still this server's to tell.
        for (Change change : unwritten) {
            if (change.kept()) {
                append(change.who(), change.notice());
            } else {
                forget(waiting.get(change.who()), change.notice());
            }
        }
    }

    private static List<Notice> noticesOf(List<Map<?, ?>> entries) {
        List<Notice> notices = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
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
            notices.add(new Notice(key.toString(), values));
        }
        return notices;
    }

    private static void forget(List<Notice> from, Notice notice) {
        if (from != null) {
            from.remove(notice);
        }
    }

    /**
     * Writes what this server kept and delivered since the last time, onto what the database holds now —
     * notices other servers kept for the same player stay.
     *
     * @return whether it reached the database
     */
    public synchronized boolean flush() {
        if (unwritten.isEmpty()) {
            return true;
        }
        List<Change> going = List.copyOf(unwritten);
        boolean written = store.update(yaml -> {
            Map<UUID, List<Notice>> theirs = new java.util.LinkedHashMap<>();
            for (Change change : going) {
                List<Notice> list = theirs.computeIfAbsent(change.who(),
                        who -> new ArrayList<>(noticesOf(yaml.getMapList("waiting." + who))));
                if (change.kept()) {
                    list.add(change.notice());
                    while (list.size() > MOST_PER_PLAYER) {
                        list.removeFirst();
                    }
                } else {
                    list.remove(change.notice());
                }
            }
            theirs.forEach((who, list) -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (Notice notice : list) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("key", notice.key());
                    entry.put("values", new LinkedHashMap<>(notice.values()));
                    entries.add(entry);
                }
                yaml.set("waiting." + who, entries.isEmpty() ? null : entries);
            });
        });
        if (written) {
            unwritten.removeAll(going);
        }
        return written;
    }
}
