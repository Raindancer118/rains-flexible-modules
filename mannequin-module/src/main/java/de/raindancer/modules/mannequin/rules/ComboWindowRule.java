package de.raindancer.modules.mannequin.rules;

/**
 * Whether a hit continues a combo, or starts a new one.
 *
 * <p>Pure arithmetic over two timestamps and a window — no clock read here, so a test can pick
 * exact millisecond values instead of racing a real one.
 */
public final class ComboWindowRule implements IMannequinRule {

    /** The module's own default — see {@code MannequinSettings#comboWindowMillis}. */
    public static final long DEFAULT_WINDOW_MILLIS = 2000L;

    /**
     * @param previousHitAt when the last hit on this mannequin landed; {@code <= 0} means there was
     *                       no previous hit, so nothing can be continued
     * @param thisHitAt      when this hit landed
     * @param windowMillis   how long a gap is still "the same combo"
     */
    public boolean continuesCombo(long previousHitAt, long thisHitAt, long windowMillis) {
        if (previousHitAt <= 0L || thisHitAt < previousHitAt) {
            return false;
        }
        return thisHitAt - previousHitAt <= Math.max(0L, windowMillis);
    }

    @Override
    public String describe() {
        return "whether a hit lands soon enough after the last one to continue the same combo";
    }
}
