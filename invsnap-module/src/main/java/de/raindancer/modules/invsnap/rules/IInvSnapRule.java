package de.raindancer.modules.invsnap.rules;

/**
 * A rule belonging to this module: decides, and does nothing else.
 *
 * <p>No side effects. Nothing saved, nothing sent, nothing scheduled, and safe to call from any
 * thread — on Folia every region has one and a rule may be asked several times a tick.
 */
public interface IInvSnapRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
