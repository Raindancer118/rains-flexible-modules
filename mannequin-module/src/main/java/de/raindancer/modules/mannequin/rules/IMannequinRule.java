package de.raindancer.modules.mannequin.rules;

/**
 * A rule belonging to this module: decides, and does nothing else.
 *
 * <p>No side effects. Nothing saved, nothing sent, nothing scheduled, and safe to call from any
 * thread — on Folia every region has one and a rule may be asked several times a tick.
 */
public interface IMannequinRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
