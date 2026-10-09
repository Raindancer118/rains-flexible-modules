package de.raindancer.modules.performance.rules;

/**
 * A rule belonging to this module: decides, and does nothing else. Safe from any thread and safe to
 * ask speculatively.
 */
public interface IPerformanceRule {

    /** What this rule decides, for a diagnostic. */
    String describe();
}
