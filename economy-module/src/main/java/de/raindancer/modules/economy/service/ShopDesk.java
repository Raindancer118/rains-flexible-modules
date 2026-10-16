package de.raindancer.modules.economy.service;

import java.util.List;
import java.util.UUID;

/**
 * The bank's shop as another server sees it: the price list, a quote, a purchase and a sale — priced and booked
 * where the money and the market are, the items moved by the server the player is on. Money comes as the bank's own
 * rendering (MiniMessage), so a price reads the same on every server.
 */
public interface ShopDesk {

    /**
     * One thing for sale, with this player's prices for one.
     *
     * @param drawer the creative drawer's name in lower case, or {@code eggs}
     * @param trend  how far supply and demand moved the price, in percent
     */
    record Line(String material, String drawer, boolean buyable, boolean sellable, String buy, String sell, int trend) {
    }

    /** What these amounts would cost to buy and bring when sold, as rendered money; empty strings where not. */
    record Quote(boolean buyable, boolean sellable, List<Price> buys, List<Price> sells, List<String> notes) {
    }

    /** @param minor the money itself, so a purchase can say "no more than this" */
    record Price(int count, String shown, long minor) {
    }

    /**
     * How a purchase or a sale went: the message key and its values to say on the player's server. Values named
     * {@code amount}, {@code balance}, {@code left} or {@code price} are rendered money.
     */
    record Settled(boolean ok, String key, List<String> values) {

        public Settled {
            values = List.copyOf(values);
        }

        public static Settled refused(String key, String... values) {
            return new Settled(false, key, List.of(values));
        }
    }

    List<Line> catalogue(UUID player);

    Quote quote(UUID player, String material, List<Integer> buyCounts, List<Integer> sellCounts);

    /** @param most the most the player agreed to pay, in minor units — a price that rose since is refused */
    Settled buy(UUID player, String material, int count, long most);

    Settled sell(UUID player, String material, int count);
}
