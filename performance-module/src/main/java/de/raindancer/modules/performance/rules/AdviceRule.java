package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.Attribution;
import de.raindancer.modules.performance.model.Cause;
import de.raindancer.modules.performance.model.Fix;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-wide advice. A plugin that eats a large share of the busy time is named; a smaller
 * simulation distance would not help with that. Only when the game itself — entities and chunks —
 * is most of the time is a smaller simulation distance offered, two chunks at a time.
 */
public final class AdviceRule implements IPerformanceRule {

    private static final int STEP = 2;

    private final int lowestSimulationDistance;
    private final int suspectPercent;

    public AdviceRule(int lowestSimulationDistance, int suspectPercent) {
        this.lowestSimulationDistance = lowestSimulationDistance;
        this.suspectPercent = suspectPercent;
    }

    public List<Fix> advice(Attribution busy, LagRule.State state, int simulationDistance) {
        if (state != LagRule.State.LAGGING && state != LagRule.State.SPIKE) {
            return List.of();
        }
        List<Fix> advice = new ArrayList<>();
        for (Attribution.Share share : busy.ranked()) {
            if (share.cause().isPlugin() && share.percent() >= suspectPercent) {
                advice.add(Fix.suspectPlugin(share.cause().plugin(), (int) Math.round(share.percent())));
            }
        }
        boolean theGame = busy.percentOf(Cause.Area.ENTITIES, Cause.Area.PATHFINDING, Cause.Area.CHUNKS) >= 50;
        if (advice.isEmpty() && state == LagRule.State.LAGGING && theGame && simulationDistance > lowestSimulationDistance) {
            advice.add(Fix.simulationDistance(Math.max(lowestSimulationDistance, simulationDistance - STEP)));
        }
        return advice;
    }

    @Override
    public String describe() {
        return "names a plugin from " + suspectPercent + " % of the busy time; offers a simulation distance down to "
                + lowestSimulationDistance;
    }
}
