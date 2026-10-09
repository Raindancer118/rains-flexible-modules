package de.raindancer.modules.farmworld.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Who holds a day pass for the farm worlds, and until when.
 *
 * <p>Kept in a file of its own so it survives a restart — somebody who paid for twenty-four hours has them
 * whatever the server does meanwhile. Written on every grant, which is rare; expired ones are dropped
 * whenever the file is read or written. An unreadable file is set aside by {@link YamlStore} rather than
 * written over, so a typo cannot cost anybody their pass.
 */
public final class FarmPasses {

    private static final String ROOT = "passes";

    private final YamlStore store;
    private final LongSupplier clock;
    private final Map<UUID, Long> until = Collections.synchronizedMap(new HashMap<>());

    public FarmPasses(Path file) {
        this(file, null);
    }

    /** @param clock milliseconds, only ever read; null takes the system clock */
    public FarmPasses(Path file, LongSupplier clock) {
        this.store = new YamlStore(file);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Reads the file, keeping only passes still running. */
    public void load() {
        YamlConfiguration yaml = store.read();
        ConfigurationSection section = yaml.getConfigurationSection(ROOT);
        until.clear();
        if (section == null) {
            return;
        }
        long now = clock.getAsLong();
        for (String key : section.getKeys(false)) {
            try {
                long expires = section.getLong(key);
                if (expires > now) {
                    until.put(UUID.fromString(key), expires);
                }
            } catch (IllegalArgumentException notAPlayer) {
                // A line somebody typed by hand. Skipped, and gone from the file at the next write.
            }
        }
    }

    public boolean isActive(UUID who) {
        Long expires = who == null ? null : until.get(who);
        return expires != null && expires > clock.getAsLong();
    }

    public Optional<Instant> expiry(UUID who) {
        return isActive(who) ? Optional.of(Instant.ofEpochMilli(until.get(who))) : Optional.empty();
    }

    public int tracked() {
        return until.size();
    }

    /** Starts a pass of this length now, or keeps the one they have if it already runs longer. */
    public void grant(UUID who, Duration length) {
        if (who == null || length == null || length.isNegative() || length.isZero()) {
            return;
        }
        long expires = clock.getAsLong() + length.toMillis();
        until.merge(who, expires, Math::max);
        save();
    }

    private void save() {
        long now = clock.getAsLong();
        synchronized (until) {
            until.values().removeIf(expires -> expires <= now);
            Map<UUID, Long> snapshot = new HashMap<>(until);
            store.write(yaml -> snapshot.forEach((player, expires) ->
                    yaml.set(ROOT + "." + player, expires)));
        }
    }

    /** Flushed on disable; harmless when nothing changed. */
    public void flush() {
        save();
    }
}
