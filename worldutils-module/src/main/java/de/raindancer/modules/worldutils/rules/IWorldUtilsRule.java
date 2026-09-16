package de.raindancer.modules.worldutils.rules;

/**
 * A rule belonging to this module: decides, and does nothing else.
 *
 * <p>No side effects. Nothing saved, nothing sent, nothing scheduled — a rule must be safe to ask
 * speculatively.
 */
public interface IWorldUtilsRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
