package de.raindancer.modules.moderation.model;

/**
 * How suspicious one player's mining has looked, learnt over time and remembered across restarts.
 *
 * <h2>Why an exponential average, the same idea as {@link ServerMiningBaseline}</h2>
 * For the same reason that class gives: a lifetime average never forgets, and a rough patch from
 * months ago would inflate this for ever afterwards, hiding whatever is actually happening this week
 * behind ancient history nobody is investigating any more. The rate here is faster than the
 * server-wide baseline's, deliberately — this is one player's own signal rather than everybody's, and
 * one lucky vein moving it a little is the point, not a defect.
 *
 * <h2>What is <em>not</em> kept here</h2>
 * The actual blocks and where they were — that is {@link MiningTrail}'s job, and it is deliberately
 * session-only. Replaying somebody's exact path from six months ago is not what a probability is for,
 * and keeping the full trail for every player who has ever mined a block would be a file that only
 * ever grows for a use that shrinks with age. This keeps only the two numbers a probability is built
 * from, plus how many ore blocks it has actually seen — shown for context, and never itself judged.
 */
public final class PlayerMiningProfile {

    /**
     * How much each new observation moves either average.
     *
     * <p>Twenty times {@link ServerMiningBaseline}'s own rate: this learns from one player rather
     * than an entire server's worth of mining, so it has far less to average over and would otherwise
     * take a very long time to say anything at all about somebody who joined last week.
     */
    private static final double LEARNING_RATE = 0.02;

    private double oreRatio;
    private double approachDirectness;
    private int observedOre;
    private long lastUpdatedEpochMillis;

    public PlayerMiningProfile() {
        this(0.0, 0.0, 0, 0L);
    }

    /** For {@code PlayerMiningProfiles#load}, reading exactly what was written. */
    public PlayerMiningProfile(double oreRatio, double approachDirectness, int observedOre,
                               long lastUpdatedEpochMillis) {
        this.oreRatio = oreRatio;
        this.approachDirectness = approachDirectness;
        this.observedOre = observedOre;
        this.lastUpdatedEpochMillis = lastUpdatedEpochMillis;
    }

    /** Folds one more mined block into the ratio, ore or not — the same shape as the server baseline. */
    public synchronized void recordBlock(boolean isOre, long nowEpochMillis) {
        double observed = isOre ? 1.0 : 0.0;
        oreRatio += LEARNING_RATE * (observed - oreRatio);
        lastUpdatedEpochMillis = nowEpochMillis;
    }

    /** Folds one more ore block's approach reading into the directness average. */
    public synchronized void recordApproach(int directnessPercent, long nowEpochMillis) {
        approachDirectness += LEARNING_RATE * (directnessPercent - approachDirectness);
        observedOre++;
        lastUpdatedEpochMillis = nowEpochMillis;
    }

    public synchronized double oreRatio() {
        return oreRatio;
    }

    public synchronized double approachDirectness() {
        return approachDirectness;
    }

    public synchronized int observedOre() {
        return observedOre;
    }

    public synchronized long lastUpdatedEpochMillis() {
        return lastUpdatedEpochMillis;
    }

    /**
     * A single number out of a hundred, meant to sort a list of everybody by who is worth actually
     * checking — never a verdict on its own.
     *
     * <h2>How it is built, and why exactly this way</h2>
     * Half of it is how far this player's own long-run ore ratio sits above whatever the server
     * currently treats as the flagging threshold — a hundred means they sit exactly at it, and it is
     * clamped there rather than let climb further, since past that point the ratio has already said
     * everything it is going to. The other half is their long-run approach directness, already a
     * percentage. The two are averaged rather than multiplied or otherwise combined, on purpose: they
     * are independent signals with independent honest explanations for reading high on their own — a
     * badlands seed inflates the first, a short natural cave inflates the second — and averaging is
     * the one combination that does not let either one alone decide the answer.
     *
     * @param effectiveThresholdPercent whatever the server is currently flagging at — see
     *                                  {@code XrayRule#effectiveThresholdPercent}, so a learnt,
     *                                  raised threshold is what this compares against too
     */
    public synchronized int probabilityPercent(int effectiveThresholdPercent) {
        double ratioSignal = effectiveThresholdPercent <= 0 ? 0.0
                : Math.min(100.0, 100.0 * (oreRatio * 100.0) / effectiveThresholdPercent);
        double approachSignal = Math.min(100.0, Math.max(0.0, approachDirectness));
        double combined = (Math.max(0.0, ratioSignal) + approachSignal) / 2.0;
        return (int) Math.round(Math.min(100.0, Math.max(0.0, combined)));
    }
}
