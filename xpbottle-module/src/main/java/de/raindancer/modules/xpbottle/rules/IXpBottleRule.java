package de.raindancer.modules.xpbottle.rules;

/**
 * A rule belonging to this module: decides, and does nothing else.
 *
 * <p>No side effects. Nothing saved, nothing sent, nothing scheduled, and safe to call from any
 * thread — on Folia every region has one and the siphon asks {@link SiphonReachRule} once per orb
 * per tick.
 */
public interface IXpBottleRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
