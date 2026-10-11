package de.raindancer.modules.anticheat.model;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One slowing state of a client (sneak key down, item in use), counted move by move. A press gets a short
 * grace before the slowdown bites — pose and use state follow the key a tick late — but the grace is a
 * budget, not a right of every press: a client that re-presses the key every tick or two would otherwise
 * never be slowed at all. Pressing again and again is also what {@link #rapid()} reports.
 */
public final class SlowState {

    /** Moves after a press that may still be unslowed. */
    public static final int GRACE = 2;
    /** The tick window the budget and the toggle count are kept over. */
    public static final int WINDOW = 40;
    /** Graces handed out per window: tapping shift now and then is honest, every tick is not. */
    public static final int GRACES_PER_WINDOW = 3;
    /** Presses in a window that no hand makes honestly. */
    public static final int RAPID_PRESSES = 8;

    private long clock;
    private int moves;
    private long graceUntil = -1;
    private final Deque<Long> presses = new ArrayDeque<>();
    private final Deque<Long> graces = new ArrayDeque<>();

    /** One judged move covering {@code ticks} ticks, made with the state on or off. */
    public void move(boolean on, int ticks) {
        clock += Math.max(1, ticks);
        while (!presses.isEmpty() && clock - presses.peekFirst() > WINDOW) {
            presses.removeFirst();
        }
        while (!graces.isEmpty() && clock - graces.peekFirst() > WINDOW) {
            graces.removeFirst();
        }
        if (!on) {
            moves = 0;
            return;
        }
        if (moves == 0) {
            presses.addLast(clock);
            if (graces.size() < GRACES_PER_WINDOW) {
                graces.addLast(clock);
                graceUntil = clock + GRACE - 1;
            } else {
                graceUntil = -1;
            }
        }
        moves++;
    }

    public boolean on() {
        return moves > 0;
    }

    /** Whether this move may still be unslowed. */
    public boolean graced() {
        return moves > 0 && clock <= graceUntil;
    }

    /** Whether it has been pressed more often than a hand does. */
    public boolean rapid() {
        return presses.size() >= RAPID_PRESSES;
    }

    public int presses() {
        return presses.size();
    }

    public void reset() {
        clock = 0;
        moves = 0;
        graceUntil = -1;
        presses.clear();
        graces.clear();
    }
}
