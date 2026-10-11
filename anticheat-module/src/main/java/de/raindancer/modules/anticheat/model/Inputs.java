package de.raindancer.modules.anticheat.model;

/**
 * The keys a client held on one tick, packed into an int so a move sample can carry them: the input
 * packet arrives on the network thread, the move it belongs to is judged a tick later on another.
 */
public final class Inputs {

    /** Nothing seen from this client yet (or no packet tap): every question about its keys is open. */
    public static final int UNKNOWN = 0;

    private static final int KNOWN = 1;
    private static final int FORWARD = 1 << 1;
    private static final int BACKWARD = 1 << 2;
    private static final int LEFT = 1 << 3;
    private static final int RIGHT = 1 << 4;
    private static final int JUMP = 1 << 5;
    private static final int SNEAK = 1 << 6;
    private static final int SPRINT = 1 << 7;

    private Inputs() {
    }

    public static int of(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
        return KNOWN | (forward ? FORWARD : 0) | (backward ? BACKWARD : 0) | (left ? LEFT : 0) | (right ? RIGHT : 0)
                | (jump ? JUMP : 0) | (sneak ? SNEAK : 0) | (sprint ? SPRINT : 0);
    }

    public static boolean known(int packed) {
        return (packed & KNOWN) != 0;
    }

    public static boolean forward(int packed) {
        return (packed & FORWARD) != 0;
    }

    public static boolean backward(int packed) {
        return (packed & BACKWARD) != 0;
    }

    public static boolean left(int packed) {
        return (packed & LEFT) != 0;
    }

    public static boolean right(int packed) {
        return (packed & RIGHT) != 0;
    }

    public static boolean jump(int packed) {
        return (packed & JUMP) != 0;
    }

    public static boolean sneak(int packed) {
        return (packed & SNEAK) != 0;
    }

    public static boolean sprint(int packed) {
        return (packed & SPRINT) != 0;
    }

    /** Whether any of the four direction keys is down. */
    public static boolean walking(int packed) {
        return (packed & (FORWARD | BACKWARD | LEFT | RIGHT)) != 0;
    }
}
