package de.raindancer.modules.ctf.model;

import java.util.Optional;
import java.util.UUID;

/**
 * One flag in a round: at home, carried, or lying where its carrier fell. Belongs to a side, or to nobody (the neutral
 * flag, side {@link #NEUTRAL}). Not thread-safe on its own — the round it belongs to is the lock.
 */
public final class Flag {

    public static final int NEUTRAL = -1;

    public enum State { HOME, CARRIED, DROPPED }

    private final int side;
    private Point home;
    private State state = State.HOME;
    private UUID carrier;
    private Point lying;
    private long since;

    public Flag(int side, Point home, long now) {
        this.side = side;
        this.home = home;
        this.since = now;
    }

    public int side() {
        return side;
    }

    public boolean isNeutral() {
        return side == NEUTRAL;
    }

    public Point home() {
        return home;
    }

    public State state() {
        return state;
    }

    public Optional<UUID> carrier() {
        return Optional.ofNullable(carrier);
    }

    /** Where it lies, when it is dropped. */
    public Optional<Point> lying() {
        return Optional.ofNullable(lying);
    }

    /** When it last changed state. */
    public long since() {
        return since;
    }

    /** Where it is now, for a carried flag the caller knows better (the carrier). */
    public Point where() {
        return state == State.DROPPED && lying != null ? lying : home;
    }

    public void take(UUID by, long now) {
        state = State.CARRIED;
        carrier = by;
        lying = null;
        since = now;
    }

    public void drop(Point at, long now) {
        state = State.DROPPED;
        carrier = null;
        lying = at;
        since = now;
    }

    public void sendHome(long now) {
        state = State.HOME;
        carrier = null;
        lying = null;
        since = now;
    }

    /** A new home, the flag staying where it is — a carried flag being moved by swapped bases. */
    public void rehome(Point to) {
        home = to;
    }

    /** Sudden death and swapped bases move a flag's home. */
    public void moveHome(Point to, long now) {
        home = to;
        sendHome(now);
    }

    public boolean isCarriedBy(UUID player) {
        return state == State.CARRIED && player.equals(carrier);
    }
}
