package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What each racer paid to enter this run — the prize pot, written to entries.yml after every change so a
 * run that dies with the server can still be refunded. With no entry fee nothing is ever written.
 */
public final class SpeedrunEntryLedger {

    private final YamlStore store;
    private final Map<UUID, Money> entries = new LinkedHashMap<>();

    public SpeedrunEntryLedger(Path file) {
        this.store = new YamlStore(file);
    }

    public synchronized void load() {
        entries.clear();
        YamlConfiguration yaml = store.read();
        ConfigurationSection paid = yaml.getConfigurationSection("entries");
        if (paid == null) {
            return;
        }
        for (String id : paid.getKeys(false)) {
            try {
                entries.put(UUID.fromString(id), Money.of(Math.max(0, paid.getLong(id))));
            } catch (IllegalArgumentException unreadable) {
                // a line nobody can read is left out; the others are still in the pot
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

    /** Puts what a racer paid into the pot. @return false, and nothing changed, when it could not be written */
    public synchronized boolean paid(UUID uuid, Money amount) {
        Money before = entries.put(uuid, amount);
        if (save()) {
            return true;
        }
        if (before == null) {
            entries.remove(uuid);
        } else {
            entries.put(uuid, before);
        }
        return false;
    }

    /** Takes a racer's entry out of the pot. Empty when there is none or the change cannot be written. */
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

    /** Empties the pot, once it is paid out or refunded. */
    public synchronized boolean clear() {
        if (entries.isEmpty()) {
            return true;
        }
        Map<UUID, Money> kept = Map.copyOf(entries);
        entries.clear();
        if (save()) {
            return true;
        }
        entries.putAll(kept);
        return false;
    }

    private boolean save() {
        Map<UUID, Money> paid = Map.copyOf(entries);
        return store.write(yaml -> paid.forEach((uuid, money) -> yaml.set("entries." + uuid, money.minor())));
    }
}
