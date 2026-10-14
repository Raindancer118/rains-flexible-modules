package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What each player has bought, in {@code unlocks.yml}. Held in memory — a menu asks on every draw.
 *
 * <p>A document of the network, one player per key ({@link KeyedDocument}): a purchase is added to the player's list
 * as the database has it now, so two servers selling to one player lose nothing of each other's. A document that
 * cannot be read refuses purchases and keeps what is held.
 */
public final class UnlockBook {

    private final KeyedDocument<Set<String>> document;

    public UnlockBook(YamlStore store) {
        this.document = new KeyedDocument<>(store, "unlocks", (yaml, key) -> {
            try {
                UUID.fromString(key);
            } catch (IllegalArgumentException notAPlayer) {
                return null;
            }
            Set<String> mine = Set.copyOf(yaml.getStringList(key));
            return mine.isEmpty() ? null : mine;
        }, (yaml, path, mine) -> yaml.set(path, mine.stream().sorted().toList()));
    }

    /** Reads the document; a read that fails keeps what is held and refuses purchases until one succeeds. */
    public void load() {
        document.load();
    }

    /** Told when another server sold something (it is read in already). Close to stop. */
    public NetworkBus.Subscription watch() {
        return document.onChange(changed -> { });
    }

    public boolean readable() {
        return document.readable();
    }

    public boolean has(UUID player, String key) {
        return of(player).contains(key);
    }

    public Set<String> of(UUID player) {
        return player == null ? Set.of() : document.get(player.toString()).orElse(Set.of());
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
    public Added add(UUID player, String key) {
        boolean[] already = {false};
        boolean written = document.change(player.toString(), now -> {
            Set<String> mine = now == null ? new HashSet<>() : new HashSet<>(now);
            already[0] = !mine.add(key);
            return Set.copyOf(mine);
        });
        if (!written) {
            return Added.NOT_SAVED;
        }
        return already[0] ? Added.ALREADY : Added.ADDED;
    }
}
