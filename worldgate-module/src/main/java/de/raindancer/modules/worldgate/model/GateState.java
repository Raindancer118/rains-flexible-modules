package de.raindancer.modules.worldgate.model;

/**
 * What one managed dimension is doing right now.
 *
 * <h2>Why {@code DRAINED} and {@code CLOSED} are not one state with a flag</h2>
 * Both block entry and both always allow leaving — {@link de.raindancer.modules.worldgate.rules.GateRule}
 * treats them identically for the boolean question. What differs is only ever shown to a player: a
 * {@code CLOSED} message reads as permanent server policy, a {@code DRAINED} one reads as "wind down
 * in progress, come back later". Collapsing the two into a single {@code blocksEntry} boolean would
 * save one enum value and cost the two admin commands (and the two messages) their separate identity
 * — a server that closes the End for good and a server draining it for an hour before a regeneration
 * are two different announcements to make, not one state read two ways.
 */
public enum GateState {
    /** Normal. Anybody may go either way. */
    OPEN,
    /** Winding down: nobody new may enter, and whoever is already inside may still leave freely. */
    DRAINED,
    /** Closed on this server, for the foreseeable future. Same portal rule as {@link #DRAINED}. */
    CLOSED
}
