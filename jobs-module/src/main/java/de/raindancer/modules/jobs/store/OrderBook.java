package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Order;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who has which order and how many they took today — order-progress.yml. Progress is written with the next flush;
 * an order taken, paid or failed is written at once. An unreadable file is never written over.
 */
public final class OrderBook {

    /**
     * One player's day: how many orders and re-rolls, and the order they are on, if any.
     *
     * @param offered whether they were made an offer they have not taken — asking again then is a re-roll. Kept
     *                here, not in memory, so leaving and coming back does not hand the re-rolls out again
     */
    public record Ledger(String day, int taken, int rerolls, boolean offered, Order order) {

        public Optional<Order> current() {
            return Optional.ofNullable(order);
        }

        public Ledger with(Order next) {
            return new Ledger(day, taken, rerolls, offered, next);
        }
    }

    private final YamlStore store;
    private final Map<UUID, Ledger> ledgers = new LinkedHashMap<>();
    private volatile boolean readable = true;
    private boolean dirty;

    public OrderBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        ledgers.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection each = players.getConfigurationSection(id);
            if (each == null) {
                continue;
            }
            try {
                ConfigurationSection written = each.getConfigurationSection("order");
                Order order = written == null ? null : new Order(written.getString("work", ""),
                        written.getString("says", ""), written.getInt("units", 1), Money.of(written.getLong("pay")),
                        written.getLong("started-at"), written.getLong("ends-at"), written.getInt("progress"),
                        Order.State.valueOf(written.getString("state", "OPEN")));
                ledgers.put(UUID.fromString(id), new Ledger(each.getString("day", ""), each.getInt("taken"),
                        each.getInt("rerolls"), each.getBoolean("offered"), order));
            } catch (IllegalArgumentException unreadable) {
                // One player's line that cannot be read is left out.
            }
        }
    }

    public boolean readable() {
        return readable;
    }

    public synchronized Optional<Ledger> of(UUID player) {
        return Optional.ofNullable(ledgers.get(player));
    }

    public synchronized Map<UUID, Ledger> all() {
        return Map.copyOf(ledgers);
    }

    public synchronized void put(UUID player, Ledger ledger) {
        ledgers.put(player, ledger);
        dirty = true;
    }

    /** @return false, and nothing changed, when it could not be written */
    public synchronized boolean putNow(UUID player, Ledger ledger) {
        if (!readable) {
            return false;
        }
        Ledger before = ledgers.put(player, ledger);
        if (save()) {
            return true;
        }
        if (before == null) {
            ledgers.remove(player);
        } else {
            ledgers.put(player, before);
        }
        return false;
    }

    public synchronized void flush() {
        if (dirty && readable) {
            save();
        }
    }

    private boolean save() {
        Map<UUID, Ledger> copy = Map.copyOf(ledgers);
        boolean written = store.write(yaml -> copy.forEach((player, ledger) -> {
            String path = "players." + player;
            yaml.set(path + ".day", ledger.day());
            yaml.set(path + ".taken", ledger.taken());
            yaml.set(path + ".rerolls", ledger.rerolls());
            yaml.set(path + ".offered", ledger.offered());
            ledger.current().ifPresent(order -> {
                yaml.set(path + ".order.work", order.work());
                yaml.set(path + ".order.says", order.says());
                yaml.set(path + ".order.units", order.units());
                yaml.set(path + ".order.pay", order.pay().minor());
                yaml.set(path + ".order.started-at", order.startedAt());
                yaml.set(path + ".order.ends-at", order.endsAt());
                yaml.set(path + ".order.progress", order.progress());
                yaml.set(path + ".order.state", order.state().name());
            });
        }));
        if (written) {
            dirty = false;
        }
        return written;
    }
}
