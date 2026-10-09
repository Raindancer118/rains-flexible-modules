package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.List;
import java.util.Optional;

/**
 * A ready-made bundle the shop sells as one item, unpacked with a right click.
 *
 * @param icon       the material the pack looks like
 * @param fixedPrice the owner's own price, or null to price it from its contents
 * @param once       whether each player may buy it only once — a starter pack
 */
public record Pack(String id, String title, String icon, List<String> description, List<PackItem> contents,
                   Money fixedPrice, boolean once) {

    public Pack {
        description = List.copyOf(description);
        contents = List.copyOf(contents);
    }

    public Optional<Money> ownPrice() {
        return Optional.ofNullable(fixedPrice).filter(Money::isPositive);
    }
}
