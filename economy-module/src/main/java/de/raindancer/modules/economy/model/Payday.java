package de.raindancer.modules.economy.model;

/** What happened to one contract on a payroll run. */
public record Payday(Contract contract, Kind kind) {

    public enum Kind {
        PAID,
        /** The employer could not pay; the contract goes on. */
        MISSED,
        /** Missed too often in a row; the contract is over. */
        ENDED
    }
}
