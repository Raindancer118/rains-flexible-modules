package de.raindancer.modules.ctf.rules;

/**
 * The control point, a second at a time. Progress runs 0..100 towards {@code leaning}; at 100 that side owns it. Another
 * side alone on the point first wears it down to 0, then builds its own.
 */
public final class ControlRule implements ICtfRule {

    /** Who owns it (-1 nobody), which side the progress is for (-1 none), and how far, 0..100. */
    public record State(int owner, int leaning, int progress) {
    }

    /** How fast an untouched, half-taken point drains, per second. */
    public static final int DRAIN = 5;

    /** @param onPoint how many of each side stand on the point */
    public State tick(State state, int[] onPoint, int captureSeconds) {
        int present = -1;
        int sidesPresent = 0;
        for (int side = 0; side < onPoint.length; side++) {
            if (onPoint[side] > 0) {
                present = side;
                sidesPresent++;
            }
        }
        if (sidesPresent > 1) {
            return state;
        }
        int step = Math.max(1, 100 / Math.max(1, captureSeconds));
        if (sidesPresent == 0) {
            if (state.owner() >= 0 || state.progress() == 0) {
                return state;
            }
            int left = Math.max(0, state.progress() - DRAIN);
            return new State(-1, left == 0 ? -1 : state.leaning(), left);
        }
        if (state.leaning() == present || state.leaning() < 0) {
            int now = Math.min(100, state.progress() + step);
            return new State(now >= 100 ? present : state.owner(), present, now);
        }
        int left = state.progress() - step;
        if (left <= 0) {
            return new State(-1, -1, 0);
        }
        return new State(state.owner(), state.leaning(), left);
    }
}
