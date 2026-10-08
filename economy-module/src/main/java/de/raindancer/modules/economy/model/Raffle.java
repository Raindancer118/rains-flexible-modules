package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A raffle: tickets sold until it ends, then one drawn. The prize is an item or money a player put up, or
 * money staff had the server put up — a server raffle, with no host.
 *
 * @param host        null for a server raffle
 * @param item        the prize item's bytes; null for a money prize
 * @param mostTickets zero for no limit
 * @param perPlayer   zero for no limit
 * @param tickets     who holds how many, in the order they first bought
 */
public record Raffle(UUID id, int number, UUID host, String hostName, byte[] item, String prizeName, Money prize,
                     Money ticketPrice, int mostTickets, int perPlayer, long startedAt, long endsAt,
                     Map<UUID, Integer> tickets) {

    public Raffle {
        tickets = Collections.unmodifiableMap(new LinkedHashMap<>(tickets));
    }

    public static Raffle item(UUID id, int number, UUID host, String hostName, byte[] item, String prizeName,
                              Money ticketPrice, int mostTickets, int perPlayer, long startedAt, long endsAt) {
        return new Raffle(id, number, host, hostName, item, prizeName, Money.ZERO, ticketPrice, mostTickets, perPlayer,
                startedAt, endsAt, Map.of());
    }

    /** Money a player puts up from their own account. */
    public static Raffle money(UUID id, int number, UUID host, String hostName, Money prize, Money ticketPrice,
                               int mostTickets, int perPlayer, long startedAt, long endsAt) {
        return new Raffle(id, number, host, hostName, null, "", prize, ticketPrice, mostTickets, perPlayer,
                startedAt, endsAt, Map.of());
    }

    /** Money the server puts up, by staff. */
    public static Raffle server(UUID id, int number, Money prize, Money ticketPrice, int mostTickets, int perPlayer,
                                long startedAt, long endsAt) {
        return new Raffle(id, number, null, "the server", null, "", prize, ticketPrice, mostTickets, perPlayer,
                startedAt, endsAt, Map.of());
    }

    public boolean serverRaffle() {
        return host == null;
    }

    public boolean moneyPrize() {
        return item == null;
    }

    public int sold() {
        return tickets.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int ticketsOf(UUID player) {
        return tickets.getOrDefault(player, 0);
    }

    public Money pot() {
        return ticketPrice.times(sold());
    }

    public boolean over(long now) {
        return now >= endsAt;
    }

    public Raffle withTickets(UUID player, int more) {
        Map<UUID, Integer> next = new LinkedHashMap<>(tickets);
        next.merge(player, more, Integer::sum);
        return new Raffle(id, number, host, hostName, item, prizeName, prize, ticketPrice, mostTickets, perPlayer,
                startedAt, endsAt, next);
    }
}
