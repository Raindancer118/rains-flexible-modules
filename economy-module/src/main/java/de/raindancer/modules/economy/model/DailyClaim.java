package de.raindancer.modules.economy.model;

/**
 * Whether /daily pays today, and what the streak becomes if it does.
 *
 * @param waitDays 0 when allowed; otherwise how long until it is (always 1: tomorrow)
 */
public record DailyClaim(boolean allowed, int streak, long day, int waitDays) {
}
