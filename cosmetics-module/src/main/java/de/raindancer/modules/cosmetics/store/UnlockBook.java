package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What each player has bought, in {@code unlocks.yml}. Held in memory — a menu asks on every draw.
 *
 * <p>A document of the network ({@code YamlStore.network}): a purchase is the player's on every server. A purchase
 * is added to the player's list as the document has it now, so two servers selling two things to one player, or
 * to two players, lose nothing of each other's; {@link #watch()} reloads when another server writes. A document
 * that could not be read is never written over, and a failed read keeps what is held: buying is refused until
 * it can be read.
 */
public final class UnlockBook {

    private final YamlStore store;
    private final Map<UUID, Set<String>> unlocks = new HashMap<>();
    private volatile boolean readable = true;

    public UnlockBook(YamlStore store) {
        this.store = store;
    }

    /** Reads the document; a read that fails keeps what is held and refuses purchases until one succeeds. */
    public synchronized void load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            readable = false;
            return;
        }
        readable = true;
        unlocks.clear();
        ConfigurationSection all = read.get().getConfigurationSection("unlocks");
        if (all == null) {
            return;
        }
        for (String key : all.getKeys(false)) {
            try {
                unlocks.put(UUID.fromString(key), new HashSet<>(all.getStringList(key)));
            } catch (IllegalArgumentException notAPlayer) {
                // Skipped; the rest still count.
            }
        }
    }

    /** Reloads when another server writes the document. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(this::load);
    }

    public boolean readable() {
        return readable;
    }

    public synchronized boolean has(UUID player, String key) {
        Set<String> mine = unlocks.get(player);
        return mine != null && mine.contains(key);
    }

    public synchronized Set<String> of(UUID player) {
        return Set.copyOf(unlocks.getOrDefault(player, Set.of()));
    }

    /** What {@link #add} came to. */
    public enum Added {
        ADDED,
        /** It was in the database already — sold by another server a moment ago. */
        ALREADY,
        /** Nothing changed: the document could not be read or written. */
        NOT_SAVED
    }

    /** Records a purchase onto what the database holds. */
    public synchronized Added add(UUID player, String key) {
        if (!readable) {
            load();
            if (!readable) {
                return Added.NOT_SAVED;
            }
        }
        String path = "unlocks." + player;
        Set<String> now = new HashSet<>();
        boolean[] already = {false};
        boolean written = store.update(yaml -> {
            now.clear();
            now.addAll(yaml.getStringList(path));
            already[0] = !now.add(key);
            yaml.set(path, now.stream().sorted().toList());
        });
        if (!written) {
            return Added.NOT_SAVED;
        }
        unlocks.put(player, now);
        return already[0] ? Added.ALREADY : Added.ADDED;
    }
}
