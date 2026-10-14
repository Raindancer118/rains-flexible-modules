package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What Auto Sell sold and has not paid yet. Drops sell the moment they fall, but are paid a few at a time,
 * so a vein is one line in the statement and not sixty. Only plain items are sold, so the counts are all
 * it takes to hand them back if a payment fails.
 */
public final class AutoSales {

    public record Batch(Money total, Map<Material, Integer> counts) {

        public int items() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final Map<UUID, Batch> owed = new HashMap<>();

    public synchronized void add(UUID player, Material material, int count, Money worth) {
        Batch old = owed.get(player);
        Map<Material, Integer> counts = old == null ? new EnumMap<>(Material.class) : old.counts();
        counts.merge(material, count, Integer::sum);
        owed.put(player, new Batch(old == null ? worth : old.total().plus(worth), counts));
    }

    public synchronized Money owed(UUID player) {
        Batch batch = owed.get(player);
        return batch == null ? Money.ZERO : batch.total();
    }

    public synchronized Money owedByAll() {
        Money total = Money.ZERO;
        for (Batch batch : owed.values()) {
            total = total.plus(batch.total());
        }
        return total;
    }

    public synchronized Map<UUID, Batch> drain() {
        Map<UUID, Batch> all = Map.copyOf(owed);
        owed.clear();
        return all;
    }

    public synchronized Optional<Batch> drain(UUID player) {
        return Optional.ofNullable(owed.remove(player));
    }
}
