package de.raindancer.modules.sends.rules;

/**
 * A rule decides and does nothing else: nothing saved, nothing sent, nothing scheduled, safe from any thread.
 * The answer is the key of the message that explains a refusal, so the caller says it in the player's words.
 */
public interface ISendsRule {

    String describe();
}
