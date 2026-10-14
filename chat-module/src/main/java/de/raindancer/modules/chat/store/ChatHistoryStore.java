package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A rolling window of what was said in public chat, and when each player was last seen leaving —
 * the two facts {@code /chathistory} needs to answer "what did I miss".
 *
 * <h2>Why a capped window, and a retention, rather than everything, forever</h2>
 * A server's whole chat history is personal data — names beside what people said — and nobody asked
 * this module to be the permanent chat log. Capacity bounds it to a plain "catch up on the last
 * while", the same shape as scrolling back in a chat client; the retention (days) bounds it in time,
 * as the GDPR wants: what is older is dropped from memory and from the document, last-quit
 * timestamps included.
 *
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a line said anywhere is read everywhere, and
 * where a player left is where "what did I miss" is measured from on whichever server they join.
 * Lines are entries keyed by time and sender, not one list, so two servers writing at once keep both
 * their lines. {@link #watch()} reloads when another server writes; what this server has said and not
 * yet written is kept across the reload, and a read that fails keeps what is held.
 *
 * <h2>Thread safety</h2>
 * State is guarded by this store's monitor, never held across the database. Persistence goes through
 * {@link #load()} and {@link #flush()}, meant to be called off the server's threads.
 */
public final class ChatHistoryStore {

    private static final LogChannel log = Log.of("chat");
    private static final long DAY_MILLIS = 86_400_000L;
    public static final int DEFAULT_RETENTION_DAYS = 365;
    public static final int MAX_RETENTION_DAYS = 730;

    /** One line of chat, as it will be shown back: who said it, in their own words, and when. */
    public record Line(UUID sender, String senderName, String text, long at) {
        /** The entry's name in the document: unique enough that two servers' lines never overwrite each other. */
        String key() {
            return at + "-" + sender.toString().substring(0, 8) + "-" + Integer.toHexString(text.hashCode());
        }
    }

    private final YamlStore store;
    private final LongSupplier clock;
    private final List<Line> lines = new ArrayList<>();
    private final Map<UUID, Long> lastQuit = new HashMap<>();
    /** Said or left here and not yet in the document — kept when a reload replaces the rest. */
    private final Map<String, Line> unwrittenLines = new HashMap<>();
    private final Map<UUID, Long> unwrittenQuits = new HashMap<>();

    /** Something was dropped here (trimmed or pruned) that the document may still hold. */
    private boolean dropped;

    /** Serialises reading and writing the document: a write merges what others wrote, and memory must catch up before the next. */
    private final Object io = new Object();

    private int capacity = 200;
    private int retentionDays = DEFAULT_RETENTION_DAYS;
    /** False until the document has been read once: writing before that could replace what we never saw. */
    private boolean loaded;

    public ChatHistoryStore(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("history.yml")), System::currentTimeMillis);
    }

    /** For a test that controls time rather than racing the real clock. */
    public ChatHistoryStore(Path dataFolder, LongSupplier clock) {
        this(YamlStore.network(dataFolder.resolve("history.yml")), clock);
    }

    public ChatHistoryStore(YamlStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** How many lines are kept before the oldest is dropped. Clamped to at least one. */
    public synchronized void capacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        trim();
    }

    /** How many days a line and a last-quit are kept. Clamped to 1..{@value #MAX_RETENTION_DAYS}; returns what applies. */
    public synchronized int retention(int days) {
        this.retentionDays = Math.max(1, Math.min(MAX_RETENTION_DAYS, days));
        prune();
        return retentionDays;
    }

    // ---------------------------------------------------------------------------- lines

    public synchronized void record(UUID sender, String senderName, String text, long at) {
        if (sender == null || text == null) {
            return;
        }
        Line line = new Line(sender, senderName == null ? "" : senderName, text, at);
        lines.add(line);
        unwrittenLines.put(line.key(), line);
        trim();
    }

    /** Every line strictly after this moment, oldest first. */
    public synchronized List<Line> linesAfter(long epochMillis) {
        List<Line> found = new ArrayList<>();
        for (Line line : lines) {
            if (line.at() > epochMillis) {
                found.add(line);
            }
        }
        return found;
    }

    /** The most recent {@code count} lines, oldest first — however many there actually are. */
    public synchronized List<Line> lastLines(int count) {
        int from = Math.max(0, lines.size() - Math.max(0, count));
        return List.copyOf(lines.subList(from, lines.size()));
    }

    private void trim() {
        while (lines.size() > capacity) {
            lines.removeFirst();
            dropped = true;
        }
    }

    /** Drops what is older than the retention. */
    private void prune() {
        long cutoff = clock.getAsLong() - retentionDays * DAY_MILLIS;
        dropped |= lines.removeIf(line -> line.at() < cutoff);
        dropped |= lastQuit.values().removeIf(at -> at < cutoff);
        unwrittenLines.values().removeIf(line -> line.at() < cutoff);
        unwrittenQuits.values().removeIf(at -> at < cutoff);
    }

    // ---------------------------------------------------------------------------- last seen leaving

    public synchronized void markQuit(UUID player, long at) {
        if (player != null) {
            lastQuit.put(player, at);
            unwrittenQuits.put(player, at);
        }
    }

    public synchronized Optional<Long> lastQuit(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(lastQuit.get(player));
    }

    // ---------------------------------------------------------------------------- persistence

    /**
     * Reads the document. Called when the module starts and when another server wrote it.
     *
     * @return false when it could not be read — what is held stays, and nothing is written until it can be
     */
    public boolean load() {
        synchronized (io) {
            return read();
        }
    }

    private boolean read() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            for (String problem : store.problems()) {
                log.warn("history.yml: {} — keeping the chat history held in memory.", problem);
            }
            return false;
        }
        YamlConfiguration yaml = read.get();
        List<Line> found = new ArrayList<>();
        Map<UUID, Long> quits = new HashMap<>();

        boolean legacy = yaml.isList("lines");
        for (Map<?, ?> raw : yaml.getMapList("lines")) {
            try {
                found.add(lineOf(raw.get("sender"), raw.get("name"), raw.get("text"), raw.get("at")));
            } catch (RuntimeException broken) {
                log.warn("history.yml: a chat line could not be read and was skipped ({})", broken.getMessage());
            }
        }
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries != null) {
            for (String key : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(key);
                try {
                    if (entry == null) {
                        throw new IllegalArgumentException("not a section");
                    }
                    found.add(lineOf(entry.get("sender"), entry.get("name"), entry.get("text"), entry.get("at")));
                } catch (RuntimeException broken) {
                    // One unreadable line is one line of history lost, not the whole document refused.
                    log.warn("history.yml: the chat line '{}' could not be read and was skipped ({})", key,
                            broken.getMessage());
                }
            }
        }
        ConfigurationSection quitSection = yaml.getConfigurationSection("last-quit");
        if (quitSection != null) {
            for (String key : quitSection.getKeys(false)) {
                try {
                    quits.put(UUID.fromString(key), quitSection.getLong(key));
                } catch (IllegalArgumentException notAnId) {
                    log.warn("history.yml: '{}' under last-quit is not a player id and was skipped.", key);
                }
            }
        }
        synchronized (this) {
            found.addAll(unwrittenLines.values());
            found.sort(Comparator.comparingLong(Line::at));
            lines.clear();
            lines.addAll(found.stream().distinct().toList());
            lastQuit.clear();
            lastQuit.putAll(quits);
            lastQuit.putAll(unwrittenQuits);
            loaded = true;
            // The 1.x list is moved into entries by the next write, which leaves it out.
            dropped |= legacy;
            prune();
            trim();
        }
        return true;
    }

    private static Line lineOf(Object sender, Object name, Object text, Object at) {
        return new Line(UUID.fromString(String.valueOf(sender)), String.valueOf(name), String.valueOf(text),
                ((Number) at).longValue());
    }

    /** Writes what this server holds; another server's lines already in the document are kept. @return whether it reached the database */
    public boolean flush() {
        synchronized (io) {
            return write();
        }
    }

    private boolean write() {
        if (!isLoaded() && !read()) {
            return false;
        }
        List<Line> writing;
        Map<UUID, Long> quits;
        Map<String, Line> sentLines;
        Map<UUID, Long> sentQuits;
        synchronized (this) {
            prune();
            if (unwrittenLines.isEmpty() && unwrittenQuits.isEmpty() && !dropped) {
                return true;
            }
            dropped = false;
            writing = List.copyOf(lines);
            quits = Map.copyOf(lastQuit);
            sentLines = Map.copyOf(unwrittenLines);
            sentQuits = Map.copyOf(unwrittenQuits);
        }
        boolean written = store.write(yaml -> {
            for (Line line : writing) {
                String base = "entries." + line.key();
                yaml.set(base + ".sender", line.sender().toString());
                yaml.set(base + ".name", line.senderName());
                yaml.set(base + ".text", line.text());
                yaml.set(base + ".at", line.at());
            }
            quits.forEach((player, at) -> yaml.set("last-quit." + player, at));
        });
        if (!written) {
            synchronized (this) {
                dropped = true;
            }
        }
        if (written) {
            synchronized (this) {
                sentLines.keySet().forEach(unwrittenLines::remove);
                sentQuits.forEach(unwrittenQuits::remove);
            }
            // The write merged what other servers wrote; the next must start from that, not from before it.
            read();
        }
        return written;
    }

    private synchronized boolean isLoaded() {
        return loaded;
    }

    /** Reloads when another server writes the document, after writing what this one holds. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(() -> {
            synchronized (io) {
                write();
                read();
            }
        });
    }
}
