package de.raindancer.modules.playerutils.rules;

/**
 * Decides, and does nothing else: no side effects, safe from any thread, cheap. See MODULE-LAYOUT.md.
 */
public interface IPlayerUtilsRule {

    /** What this decides, for a diagnostic. */
    String describe();
}
