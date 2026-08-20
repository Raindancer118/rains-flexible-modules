package de.raindancer.modules.xaeromap.rules;

/**
 * Something this module decides, and does nothing about.
 *
 * <p>No side effects, safe from any thread, and cheap — the claim-to-chunk rules here run over every
 * claim on the server on a timer, and the map ones run once per player per sync. See
 * {@code MODULE-LAYOUT.md} for what the interface promises.
 */
public interface IXaeroMapRule {

    /** What this rule decides, in a sentence, for the diagnostic that lists them. */
    String describe();
}
