package de.raindancer.modules.oneblock.model;

import java.util.Locale;
import java.util.Optional;

/**
 * An admin's word over an island's world: kept up, or kept down, until a time or for good. Stronger than anything
 * players do — a held-down island cannot be entered, a held-up one is never unloaded.
 *
 * @param until epoch milliseconds, or {@link #FOREVER}
 * @param by    who set it, for the admin pages
 */
public record Hold(Kind kind, long until, String by) {

    public static final long FOREVER = Long.MAX_VALUE;

    public enum Kind {
        UP, DOWN;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> fromWire(String raw) {
            for (Kind kind : values()) {
                if (kind.wire().equalsIgnoreCase(raw == null ? "" : raw.strip())) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    public Hold {
        by = by == null ? "" : by;
    }

    public boolean forever() {
        return until == FOREVER;
    }

    public boolean activeAt(long now) {
        return now < until;
    }

    public boolean keepsUp(long now) {
        return kind == Kind.UP && activeAt(now);
    }

    public boolean keepsDown(long now) {
        return kind == Kind.DOWN && activeAt(now);
    }
}
