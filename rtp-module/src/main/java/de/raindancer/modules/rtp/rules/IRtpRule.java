package de.raindancer.modules.rtp.rules;

/**
 * A rule belonging to this module: decides, and does nothing else.
 *
 * <p>No side effects. Nothing saved, nothing sent, nothing scheduled — a rule must be safe to ask
 * speculatively, which is exactly what a command asks before spending a warm-up on a trip that was
 * never going to be allowed.
 */
public interface IRtpRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
