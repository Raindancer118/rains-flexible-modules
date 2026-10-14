package de.raindancer.modules.moderation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Alerts as staff can read them: the first flag of a player is told at once, everything after it within a
 * window is gathered into one line — "Fly ×12, Speed ×4" — instead of a line per check per second. A check
 * that alerts on its very first flag waits for a second one before it reaches chat; the log keeps every flag.
 */
public final class AlertDigest {

    public static final long WINDOW_MILLIS = 5_000;
    /** How long a single first flag of such a check is remembered while waiting for a second. */
    static final long LONE_FLAG_MILLIS = 30_000;

    /** One line for staff. */
    public record Line(UUID player, String name, Map<CheckType, Integer> counts, double level, String detail, int ping,
                       CheckType check) {
    }

    private static final class Window {
        long opened;
        boolean open;
        final Map<CheckType, Integer> counts = new EnumMap<>(CheckType.class);
        double level;
        String detail;
        int ping;
        CheckType last;
        String name;
    }

    private final LongSupplier clock;
    private final Map<UUID, Window> windows = new HashMap<>();
    /** Per player and first-flag check: when its single unannounced flag came. */
    private final Map<String, Long> lone = new HashMap<>();

    public AlertDigest(LongSupplier clock) {
        this.clock = clock;
    }

    /** A flag past its alert level. @return the line to send now, if this one is told at once */
    public synchronized Optional<Line> offer(UUID player, String name, CheckType check, double level, String detail, int ping) {
        long now = clock.getAsLong();
        if (check.alertAt() <= 1 && !check.impossible()) {
            String key = player + ":" + check.key();
            Long first = lone.get(key);
            if (first == null || now - first > LONE_FLAG_MILLIS) {
                lone.put(key, now);
                return Optional.empty();
            }
        }
        Window window = windows.computeIfAbsent(player, ignored -> new Window());
        window.name = name;
        if (!window.open || now - window.opened >= WINDOW_MILLIS && window.counts.isEmpty()) {
            window.open = true;
            window.opened = now;
            window.counts.clear();
            window.level = 0;
            return Optional.of(new Line(player, name, Map.of(check, 1), level, detail, ping, check));
        }
        window.counts.merge(check, 1, Integer::sum);
        window.level = Math.max(window.level, level);
        window.detail = detail;
        window.ping = ping;
        window.last = check;
        return Optional.empty();
    }

    /** Lines whose window has run out, each told once. Call every second or so. */
    public synchronized List<Line> due() {
        long now = clock.getAsLong();
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<UUID, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            if (!window.open || now - window.opened < WINDOW_MILLIS) {
                continue;
            }
            if (window.counts.isEmpty()) {
                window.open = false;
                continue;
            }
            lines.add(new Line(entry.getKey(), window.name, Collections.unmodifiableMap(new EnumMap<>(window.counts)),
                    window.level, window.detail, window.ping, window.last));
            window.counts.clear();
            window.level = 0;
            window.opened = now;
        }
        lone.values().removeIf(at -> now - at > LONE_FLAG_MILLIS);
        return lines;
    }

    public synchronized void forget(UUID player) {
        windows.remove(player);
        lone.keySet().removeIf(key -> key.startsWith(player.toString()));
    }
}
