package de.raindancer.modules.economy.rules;

/**
 * A rule belonging to this module: decides, and does nothing else. No side effects, safe from any
 * thread — the ledger asks {@link BalanceRule} under its lock on every change.
 */
public interface IEconomyRule {

    String describe();
}
