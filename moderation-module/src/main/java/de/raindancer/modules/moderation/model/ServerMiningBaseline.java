package de.raindancer.modules.moderation.model;

/**
 * What "ordinary" mining looks like on this server, learnt from everybody's digging rather than
 * assumed.
 *
 * <h2>Why a fixed percentage is not enough on its own</h2>
 * A server sitting on a badlands biome or a heavily ancient-debris seed has more valuable ore per block
 * of stone than one that does not, purely by terrain — and a threshold tuned for one is wrong for the
 * other on day one. This watches every block anybody mines, server-wide, and settles on the ratio that
 * is actually normal here, so the fixed setting becomes a floor rather than the whole answer — see
 * {@code XrayRule#effectiveThresholdPercent}.
 *
 * <h2>Why an exponential moving average and not a lifetime total</h2>
 * A lifetime average never forgets: a week where half the server was strip-mining an exposed ravine
 * would inflate "normal" for ever, and after that a real x-ray user could hide inside the inflated
 * number permanently. An exponential average discounts old observations geometrically, so what
 * happened a month ago matters far less than what happened this evening — the server's notion of
 * normal drifts with how the server actually plays now.
 *
 * <h2>The weakness worth knowing about</h2>
 * This learns from <em>everybody</em>, cheaters included — a server where a meaningful fraction of
 * players are already x-raying will learn a higher "normal" than it should, which is exactly why
 * {@code XrayRule} is handed this as a floor-raiser only, never as a way to lower the configured
 * minimum. Learning makes the threshold adapt to honest terrain differences; it is not asked to decide
 * on its own that x-ray is normal here.
 */
public final class ServerMiningBaseline {

    /**
     * How much each new observation moves the average. Small on purpose: one player's one ore block
     * must not swing a server-wide number, and the whole point is a figure that reflects a great many
     * blocks rather than the last handful.
     */
    private static final double LEARNING_RATE = 0.001;

    private double ratio;

    /** Folds one more mined block into the running average. */
    public synchronized void record(boolean isOre) {
        double observed = isOre ? 1.0 : 0.0;
        ratio += LEARNING_RATE * (observed - ratio);
    }

    /** The learnt ratio of ore to everything mined, as the server actually plays right now. */
    public synchronized double ratio() {
        return ratio;
    }
}
