package de.raindancer.modules.guilds.rules;

/**
 * Decides, and does nothing else: no side effects, safe from any thread, so a screen may ask "would this be
 * allowed?" to grey a button. See MODULE-LAYOUT.md.
 */
public interface IGuildsRule {

    /** What this rule decides, in a sentence — what a diagnostic names. */
    String describe();
}
