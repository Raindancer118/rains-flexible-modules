package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What each tribute paid to enter this round, and the order the tributes fell in — the prize pot, written to
 * entries.yml after every change. A round survives a restart (the session is on disk too), so the pot has to
 * survive it with the round: what is read back here is what is refunded or paid out when the round ends.
 * With no entry fee nothing is ever written.
 */
public final class EntryLedger {

    private final YamlStore store;
    private final Map<UUID, Money> entries = new LinkedHashMap<>();
    private final List<UUID> fallen = new ArrayList<>();

    public EntryLedger(Path file) {
        this.store = new YamlStore(file);
    }

    public synchronized void load() {
        entries.clear();
        fallen.clear();
        YamlConfiguration yaml = store.read();
        ConfigurationSection paid = yaml.getConfigurationSection("entries");
        if (paid != null) {
            for (String id : paid.getKeys(false)) {
                try {
                    entries.put(UUID.fromString(id), Money.of(Math.max(0, paid.getLong(id))));
                } catch (IllegalArgumentException unreadable) {
                    // a line nobody can read is left out; the others are still in the pot
                }
            }
        }
        for (String id : yaml.getStringList("fallen")) {
            try {
                fallen.add(UUID.fromString(id));
            } catch (IllegalArgumentException unreadable) {
                // the same
            }
        }
    }

    public synchronized Money pot() {
        return entries.values().stream().reduce(Money.ZERO, Money::plus);
    }

    public synchronized Map<UUID, Money> entries() {
        return Map.copyOf(entries);
    }

    public synchronized boolean has(UUID uuid) {
        return entries.containsKey(uuid);
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized List<UUID> fallen() {
        return List.copyOf(fallen);
    }

    /** Puts what a tribute paid into the pot. @return false, and nothing changed, when it could not be written */
    public synchronized boolean paid(UUID uuid, Money amount) {
        Money before = entries.put(uuid, amount);
        if (save()) {
            return true;
        }
        restore(uuid, before);
        return false;
    }

    /** Takes a tribute's entry out of the pot, for a refund. Empty when there is none or it cannot be written. */
    public synchronized Optional<Money> take(UUID uuid) {
        Money before = entries.remove(uuid);
        if (before == null) {
            return Optional.empty();
        }
        if (save()) {
            return Optional.of(before);
        }
        entries.put(uuid, before);
        return Optional.empty();
    }

    /** Puts an entry back after a refund that did not go through. */
    public synchronized void putBack(UUID uuid, Money amount) {
        entries.put(uuid, amount);
        save();
    }

    public synchronized boolean fell(UUID uuid) {
        fallen.remove(uuid);
        fallen.add(uuid);
        return save();
    }

    public synchronized boolean revived(UUID uuid) {
        return !fallen.remove(uuid) || save();
    }

    /** Empties the pot and the order of falling, once the round is paid out or refunded. */
    public synchronized boolean clear() {
        if (entries.isEmpty() && fallen.isEmpty()) {
            return true;
        }
        Map<UUID, Money> keptEntries = Map.copyOf(entries);
        List<UUID> keptFallen = List.copyOf(fallen);
        entries.clear();
        fallen.clear();
        if (save()) {
            return true;
        }
        entries.putAll(keptEntries);
        fallen.addAll(keptFallen);
        return false;
    }

    private void restore(UUID uuid, Money before) {
        if (before == null) {
            entries.remove(uuid);
        } else {
            entries.put(uuid, before);
        }
    }

    private boolean save() {
        Map<UUID, Money> paid = Map.copyOf(entries);
        List<UUID> order = List.copyOf(fallen);
        return store.write(yaml -> {
            paid.forEach((uuid, money) -> yaml.set("entries." + uuid, money.minor()));
            yaml.set("fallen", order.stream().map(UUID::toString).toList());
        });
    }
}
