package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/**
 * What a pack costs one buyer.
 *
 * @param contents what its contents would cost them one by one
 * @param price    what the pack costs them
 */
public record PackPrice(Money contents, Money price) {

    public boolean saves() {
        return price.minor() < contents.minor();
    }
}
