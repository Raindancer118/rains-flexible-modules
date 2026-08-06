package de.raindancer.modules.hungergames.model;

/**
 * A contradiction found in a border configuration.
 *
 * <h2>Why this is a value and not a thrown exception</h2>
 * Because finding one is not an error, it is a thing to show an admin with options. {@link BorderMath}
 * validates a whole configuration and hands back every conflict it finds, in order, and the border screen
 * turns each one into a page offering {@code BorderResolution} options with their computed effects —
 * nothing is ever adjusted silently. That is the fairness invariant this module promises: the maximum
 * edge speed is a ceiling an admin cannot accidentally exceed, and every fix to a conflict is one they
 * chose and confirmed.
 *
 * @param phaseIndex   the phase the conflict is in
 * @param type         what kind of contradiction it is
 * @param impliedSpeed the edge speed the phase would actually run at, in blocks per second, where relevant
 * @param limit        the limit that was violated (e.g. the max edge speed, or the minimum size)
 */
public record BorderConflict(int phaseIndex, Type type, double impliedSpeed, double limit) {

    public enum Type {
        /** The phase's implied edge speed exceeds {@code maxEdgeSpeed}. */
        SPEED_EXCEEDS_MAX,

        /** The target size is below {@code minimumSize}. */
        TARGET_BELOW_MINIMUM,

        /** The target size is not smaller than the phase's starting size. */
        TARGET_NOT_SHRINKING,

        /** The phase's start time plus its duration runs past the configured game length. */
        EXCEEDS_GAME_TIME,

        /** The phases' time triggers are not in ascending order. */
        PHASES_OUT_OF_ORDER
    }
}
