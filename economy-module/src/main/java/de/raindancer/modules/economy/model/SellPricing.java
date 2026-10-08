package de.raindancer.modules.economy.model;

/** How the shop decides what it pays for something sold to it. */
public enum SellPricing {
    /** A fraction of every item's value; anything in the custom list pays its own price instead. */
    AUTOMATIC,
    /** Only what is in the custom sell-price list can be sold, at exactly that price. */
    CUSTOM_ONLY
}
