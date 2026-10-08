package de.raindancer.modules.economy.model;

/** The two kinds of cash. */
public enum Form {
    /** Stacks like any item, carries no serial. Small change. */
    COIN,
    /** One per slot, numbered, and paid in once only. */
    NOTE
}
