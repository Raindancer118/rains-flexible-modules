package de.raindancer.modules.ctf.rules;

/**
 * Decides, and does nothing else: no side effects, safe from any thread, cheap. See MODULE-LAYOUT.md.
 */
public interface ICtfRule {

    /** What a diagnostic names this rule. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
