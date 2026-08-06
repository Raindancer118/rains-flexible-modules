package de.raindancer.modules.hungergames.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A round's complete border configuration: where it starts, how far it may ever shrink, how fast it is
 * ever allowed to move, and the phases that carry it there.
 *
 * @param initialSize  the starting diameter, in blocks
 * @param minimumSize  the absolute floor — no phase may target below this
 * @param maxEdgeSpeed the fairness ceiling, in blocks per second per edge (default 2.5) — see the class
 *                     note on {@link BorderConflict}
 * @param phases       the phases, in the order they run
 */
public record BorderSettings(
        double initialSize,
        double minimumSize,
        double maxEdgeSpeed,
        List<BorderPhaseConfig> phases) {

    public BorderSettings {
        if (initialSize <= 0) {
            throw new IllegalArgumentException("initialSize must be > 0");
        }
        if (minimumSize < 0) {
            throw new IllegalArgumentException("minimumSize must not be negative");
        }
        if (maxEdgeSpeed <= 0) {
            throw new IllegalArgumentException("maxEdgeSpeed must be > 0");
        }
        phases = List.copyOf(phases);
    }

    /** The size a phase starts shrinking from — the previous phase's target, or {@link #initialSize} for the first. */
    public double startSizeOf(int phaseIndex) {
        return phaseIndex == 0 ? initialSize : phases.get(phaseIndex - 1).targetSize();
    }

    /** A copy with one phase replaced. */
    public BorderSettings withPhase(int index, BorderPhaseConfig phase) {
        List<BorderPhaseConfig> copy = new ArrayList<>(phases);
        copy.set(index, phase);
        return new BorderSettings(initialSize, minimumSize, maxEdgeSpeed, copy);
    }
}
