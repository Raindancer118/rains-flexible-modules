package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.TickWindow;

/** How the server is doing, from its recent ticks. */
public final class LagRule implements IPerformanceRule {

    public enum State { HEALTHY, STRAINED, LAGGING, SPIKE }

    /** Five seconds of ticks: fewer than that, right after a start, says nothing yet. */
    static final int FEWEST_TICKS = 100;

    private final double strainedMs;
    private final double spikeMs;

    /**
     * @param strainedMs an average tick this slow leaves no headroom
     * @param spikeMs    a single tick this slow is a spike
     */
    public LagRule(double strainedMs, double spikeMs) {
        this.strainedMs = strainedMs;
        this.spikeMs = spikeMs;
    }

    public State judge(TickWindow window) {
        if (window.size() < FEWEST_TICKS) {
            return State.HEALTHY;
        }
        double mean = window.mean();
        if (mean > TickWindow.BUDGET_MS) {
            return State.LAGGING;
        }
        if (window.worst() >= spikeMs) {
            return State.SPIKE;
        }
        return mean >= strainedMs ? State.STRAINED : State.HEALTHY;
    }

    @Override
    public String describe() {
        return "lagging over " + TickWindow.BUDGET_MS + " ms a tick on average, strained from " + strainedMs
                + " ms, a spike from " + spikeMs + " ms";
    }
}
