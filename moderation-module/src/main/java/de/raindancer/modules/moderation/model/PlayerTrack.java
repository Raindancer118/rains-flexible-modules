package de.raindancer.modules.moderation.model;

import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** What this server holds about one online player: their levels, what they may skip, their connection. */
public final class PlayerTrack {

    private final UUID id;
    private final String name;
    private final LongSupplier clock;
    private final Violations violations;

    /** Skips everything — the bypass permission. */
    public volatile boolean bypassAll;
    /** Skips these — their permissions. */
    public final Set<CheckType> bypassed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Being kicked right now: nothing more is judged or said about them. */
    public volatile boolean kicking;
    /** Milliseconds, as the server measures it. */
    public volatile int ping;

    public PlayerTrack(UUID id, String name, LongSupplier clock) {
        this.id = id;
        this.name = name;
        this.clock = clock;
        this.violations = new Violations(clock);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Violations violations() {
        return violations;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Everything held against this player here, started over — as if they had just joined for the first time. */
    public synchronized void measureAfresh() {
        violations.resetAll();
    }
}
