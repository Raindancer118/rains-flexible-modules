package de.raindancer.modules.worldgate.model;

/**
 * Both dimensions' state at once — what {@link de.raindancer.modules.worldgate.store.GateStateStore}
 * reads and writes, and what {@link de.raindancer.modules.worldgate.service.WorldGateService} holds
 * live.
 */
public record GateStates(GateState nether, GateState end) {

    /** A fresh install, or a file that has never been written: nothing is locked. */
    public static final GateStates ALL_OPEN = new GateStates(GateState.OPEN, GateState.OPEN);

    public GateStates {
        nether = nether == null ? GateState.OPEN : nether;
        end = end == null ? GateState.OPEN : end;
    }

    /** The state of one dimension, without the caller having to switch on which field that is. */
    public GateState of(Dimension dimension) {
        return dimension == Dimension.NETHER ? nether : end;
    }

    /** The same pair, with one dimension's state replaced. */
    public GateStates with(Dimension dimension, GateState state) {
        return dimension == Dimension.NETHER ? new GateStates(state, end) : new GateStates(nether, state);
    }
}
