package de.raindancer.modules.voicebridge.rules;

/**
 * A rule belonging to this module: decides, and does nothing else. No side effects, and safe from
 * any thread — the speaker rule is asked on the voice chat's network thread for every packet.
 */
public interface IVoiceBridgeRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
