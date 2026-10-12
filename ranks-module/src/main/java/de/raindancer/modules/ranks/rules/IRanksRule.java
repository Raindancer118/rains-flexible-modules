package de.raindancer.modules.ranks.rules;

/** Decides, and does nothing else — no side effects, safe from any thread. */
public interface IRanksRule {

    String describe();
}
