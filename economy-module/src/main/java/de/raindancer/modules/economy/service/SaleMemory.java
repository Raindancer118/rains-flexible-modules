package de.raindancer.modules.economy.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What each player sold to the shop lately, item by item — so selling the same thing again soon pays less.
 * Kept in memory: a restart forgets, which only ever errs in the player's favour.
 */
final class SaleMemory {

    private record Sale(long at, int count) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Map<String, Deque<Sale>>> sales = new ConcurrentHashMap<>();

    SaleMemory(LongSupplier clock) {
        this.clock = clock;
    }

    /** How many of {@code material} this player sold within the last {@code minutes}. */
    int soldLately(UUID player, String material, int minutes) {
        Map<String, Deque<Sale>> mine = sales.get(player);
        if (mine == null) {
            return 0;
        }
        synchronized (mine) {
            Deque<Sale> recent = mine.get(material);
            if (recent == null) {
                return 0;
            }
            long since = clock.getAsLong() - minutes * 60_000L;
            while (!recent.isEmpty() && recent.peekFirst().at() < since) {
                recent.pollFirst();
            }
            int count = 0;
            for (Sale sale : recent) {
                count += sale.count();
            }
            return count;
        }
    }

    void sold(UUID player, String material, int count) {
        Map<String, Deque<Sale>> mine = sales.computeIfAbsent(player, id -> new HashMap<>());
        synchronized (mine) {
            mine.computeIfAbsent(material, key -> new ArrayDeque<>()).addLast(new Sale(clock.getAsLong(), count));
        }
    }

    void forget(UUID player) {
        sales.remove(player);
    }
}
