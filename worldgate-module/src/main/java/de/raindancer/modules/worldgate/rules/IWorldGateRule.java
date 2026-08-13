package de.raindancer.modules.worldgate.rules;

/**
 * A rule belonging to this module: decides, and does nothing else.
 *
 * <p>No side effects. Nothing saved, nothing sent, nothing scheduled — a rule must be safe to ask
 * speculatively, which is exactly what a screen would ask to grey a button before a player has done
 * anything at all.
 */
public interface IWorldGateRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
