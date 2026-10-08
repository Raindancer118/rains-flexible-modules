package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/** One run of the wealth tax: how many accounts paid, and how much left the economy. */
public record TaxRun(int accounts, Money total) {
}
