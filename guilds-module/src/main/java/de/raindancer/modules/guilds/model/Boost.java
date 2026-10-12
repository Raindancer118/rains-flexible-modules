package de.raindancer.modules.guilds.model;

import java.util.Optional;
import java.util.UUID;

/** A server-wide experience boost staff started: so many percent more, until then. */
public record Boost(int percent, long until, Optional<UUID> by) {

    public static final Boost NONE = new Boost(0, 0, Optional.empty());

    public Boost {
        percent = Math.max(0, percent);
        by = by == null ? Optional.empty() : by;
    }

    public boolean isOnAt(long now) {
        return percent > 0 && now < until;
    }

    public int percentAt(long now) {
        return isOnAt(now) ? percent : 0;
    }
}
