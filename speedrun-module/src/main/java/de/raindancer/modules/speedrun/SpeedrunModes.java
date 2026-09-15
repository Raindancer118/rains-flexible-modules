package de.raindancer.modules.speedrun;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Every {@link SpeedrunMode} installed on this server right now.
 *
 * <h2>Static, like {@link SpeedrunCommands}</h2>
 * The module offering a mode ({@code ManhuntModule.enable}) and the lobby reading it never meet, and
 * neither can be handed the other — the lobby is built in this module's own enable, possibly inside a
 * different plugin. A shelf both can reach is the only meeting point there is.
 *
 * <h2>{@link #withdraw} is not politeness</h2>
 * A mode left on the shelf after its module unloaded hands the next start to a plugin that is no
 * longer there. {@code SpeedrunModule.disable} empties the shelf outright for the same reason: on a
 * reload it may come back before the modes do.
 */
public final class SpeedrunModes {

    /** Sorted by id, so the lobby menu cycles through them in the same order every time. */
    private static final Map<String, SpeedrunMode> OFFERED = new ConcurrentSkipListMap<>();

    private SpeedrunModes() {
    }

    /** A mode's module is up. Offering the same id again replaces the earlier one. */
    public static void offer(SpeedrunMode mode) {
        Objects.requireNonNull(mode, "mode");
        String id = normalised(mode.id());
        if (id.isEmpty()) {
            throw new IllegalArgumentException("A game mode needs an id to be chosen and withdrawn by.");
        }
        OFFERED.put(id, mode);
    }

    /** A mode's module is going away, and the mode with it. */
    public static void withdraw(String id) {
        if (id != null) {
            OFFERED.remove(normalised(id));
        }
    }

    /** The mode stored as {@code id}. Empty for the plain race — a blank id — and for one not installed. */
    public static Optional<SpeedrunMode> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(OFFERED.get(normalised(id)));
    }

    /** Every installed mode, in id order. */
    public static List<SpeedrunMode> offered() {
        return List.copyOf(OFFERED.values());
    }

    /**
     * The id after {@code current} in the menu's cycle: the plain race (a blank id), then every
     * installed mode in order, then the plain race again. An id that is not installed starts over at
     * the plain race rather than jumping somewhere arbitrary.
     */
    public static String next(String current) {
        List<String> ids = List.copyOf(OFFERED.keySet());
        if (ids.isEmpty()) {
            return "";
        }
        if (current == null || current.isBlank()) {
            return ids.getFirst();
        }
        int at = ids.indexOf(normalised(current));
        if (at < 0 || at + 1 >= ids.size()) {
            return "";
        }
        return ids.get(at + 1);
    }

    /** Empties the shelf — for tests, and for a full reload. */
    public static void clear() {
        OFFERED.clear();
    }

    private static String normalised(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
