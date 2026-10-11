package de.raindancer.modules.economy.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Up to 0.28.0 enchanted books cost a fixed price per level ({@code shop.enchant-price}) and sold for another
 * ({@code shop.enchant-value}). Books are priced from what they cost to make now; the two lines stay in the
 * owner's file untouched (unknown keys are never written away), and the log says once per start that they no
 * longer count and what took their place.
 */
public final class LegacyEnchantSettings {

    static final List<String> KEYS = List.of("shop.enchant-price", "shop.enchant-value");

    private LegacyEnchantSettings() {
    }

    /**
     * @param file     what the settings file holds under a key, or null
     * @param examples a few of the new prices, for the owner to compare
     */
    public static Optional<String> notice(Function<String, Object> file, String examples) {
        List<String> found = new ArrayList<>();
        for (String key : KEYS) {
            Object value = file.apply(key);
            if (value != null) {
                found.add(key + " (" + value + ")");
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join(" and ", found) + (found.size() == 1 ? " is" : " are")
                + " no longer read: enchanted books are priced like every other item now — a plain book plus what "
                + "making the enchantment costs (lapis, experience at the bottle's price, the anvil), times how "
                + "useful it is (shop.enchant-worth), with supply and demand per enchantment and level. "
                + (examples.isBlank() ? "" : "Now: " + examples + ". ")
                + "To price one by hand, use shop.values, shop.buy-prices or shop.sell-prices with a line like "
                + "'enchanted_book:mending:1 20000'. The old lines are left in config.yml untouched; delete them "
                + "to stop this message.");
    }
}
