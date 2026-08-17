package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A rolling window of what was said in public chat, and when each player was last seen leaving —
 * the two facts {@code /chathistory} needs to answer "what did I miss".
 *
 * <h2>Why a capped window rather than everything, forever</h2>
 * A server's whole chat history is personal data — names beside what people said, kept indefinitely
 * — and nobody asked this module to be the server's permanent chat log. Capacity bounds it to a
 * plain "catch up on the last while", the same shape as scrolling back in a chat client, and the
 * oldest line is silently dropped once a newer one pushes the count over the cap.
 *
 * <h2>Why "last seen leaving" is kept without a cap</h2>
 * It is one timestamp per player who has ever chatted here, not a growing log — the cost of keeping
 * it forever is the same as keeping it for a week, so there is nothing to bound.
 *
 * <h2>Thread safety</h2>
 * The deque and the map are concurrent, because a line is recorded from the chat thread while a
 * command reads it from the main thread. Persistence goes through {@link #load()} and {@link #flush()},
 * both meant to be called off the server's threads — see {@code YamlStore}'s own note.
 */
public final class ChatHistoryStore {

    private static final LogChannel log = Log.of("chat");

    /** One line of chat, as it will be shown back: who said it, in their own words, and when. */
    public record Line(UUID sender, String senderName, String text, long at) {
    }

    private final YamlStore store;
    private final ConcurrentLinkedDeque<Line> lines = new ConcurrentLinkedDeque<>();
    private final Map<UUID, Long> lastQuit = new ConcurrentHashMap<>();

    private volatile int capacity = 200;

    public ChatHistoryStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("history.yml"));
    }

    /** How many lines are kept before the oldest is dropped. Clamped to at least one. */
    public void capacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        trim();
    }

    // ---------------------------------------------------------------------------- lines

    public void record(UUID sender, String senderName, String text, long at) {
        if (sender == null || text == null) {
            return;
        }
        lines.addLast(new Line(sender, senderName == null ? "" : senderName, text, at));
        trim();
    }

    /** Every line strictly after this moment, oldest first. */
    public List<Line> linesAfter(long epochMillis) {
        List<Line> found = new ArrayList<>();
        for (Line line : lines) {
            if (line.at() > epochMillis) {
                found.add(line);
            }
        }
        return found;
    }

    /** The most recent {@code count} lines, oldest first — however many there actually are. */
    public List<Line> lastLines(int count) {
        List<Line> all = new ArrayList<>(lines);
        int from = Math.max(0, all.size() - Math.max(0, count));
        return List.copyOf(all.subList(from, all.size()));
    }

    private void trim() {
        while (lines.size() > capacity) {
            lines.pollFirst();
        }
    }

    // ---------------------------------------------------------------------------- last seen leaving

    public void markQuit(UUID player, long at) {
        if (player != null) {
            lastQuit.put(player, at);
        }
    }

    public Optional<Long> lastQuit(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(lastQuit.get(player));
    }

    // ---------------------------------------------------------------------------- persistence

    /** Reads what is on disk. Called once, when the module starts. */
    public void load() {
        var yaml = store.read();
        lines.clear();
        lastQuit.clear();

        for (Map<?, ?> raw : yaml.getMapList("lines")) {
            try {
                UUID sender = UUID.fromString(String.valueOf(raw.get("sender")));
                String name = String.valueOf(raw.get("name"));
                String text = String.valueOf(raw.get("text"));
                long at = ((Number) raw.get("at")).longValue();
                lines.addLast(new Line(sender, name, text, at));
            } catch (RuntimeException broken) {
                // One unreadable line is one line of history lost, not the whole file refused.
                log.warn("history.yml: a chat line could not be read and was skipped ({})",
                        broken.getMessage());
            }
        }
        trim();

        ConfigurationSection quits = yaml.getConfigurationSection("last-quit");
        if (quits != null) {
            for (String key : quits.getKeys(false)) {
                try {
                    lastQuit.put(UUID.fromString(key), quits.getLong(key));
                } catch (IllegalArgumentException notAnId) {
                    log.warn("history.yml: '{}' under last-quit is not a player id and was skipped.",
                            key);
                }
            }
        }
        for (String problem : store.problems()) {
            log.warn("history.yml: {}", problem);
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (Line line : lines) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sender", line.sender().toString());
                entry.put("name", line.senderName());
                entry.put("text", line.text());
                entry.put("at", line.at());
                raw.add(entry);
            }
            yaml.set("lines", raw);
            for (Map.Entry<UUID, Long> entry : lastQuit.entrySet()) {
                yaml.set("last-quit." + entry.getKey(), entry.getValue());
            }
        });
    }
}
