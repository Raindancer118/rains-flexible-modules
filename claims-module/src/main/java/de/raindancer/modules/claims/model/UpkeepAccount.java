package de.raindancer.modules.claims.model;

/**
 * One owner's standing with the upkeep: when they are next billed, what they owe, and since when.
 *
 * @param nextDue epoch millis of the next bill
 * @param owed    unpaid upkeep in minor currency units, already priced when it was missed
 * @param since   epoch millis of the first bill that went unpaid and has not been cleared; 0 when nothing is owed
 */
public record UpkeepAccount(long nextDue, long owed, long since) {

    public boolean inArrears() {
        return owed > 0;
    }
}
