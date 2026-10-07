package de.raindancer.modules.cosmetics.store;

import de.raindancer.modules.cosmetics.model.TeleportLookChoice;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * A player's teleport sounds and waiting particle, in their own persistent data like their worn
 * particle — see {@link ParticleChoices}. Only touch a player on their own thread.
 */
public final class TeleportChoices {

    private static final NamespacedKey DEPART = key("teleport-depart");
    private static final NamespacedKey ARRIVE = key("teleport-arrive");
    private static final NamespacedKey WAIT = key("teleport-wait");
    private static final NamespacedKey TICK = key("teleport-tick");

    private static NamespacedKey key(String name) {
        NamespacedKey key = NamespacedKey.fromString("rainscosmetics:" + name);
        if (key == null) {
            throw new IllegalStateException("rainscosmetics:" + name + " is not a valid key");
        }
        return key;
    }

    public TeleportLookChoice read(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        return new TeleportLookChoice(data.get(DEPART, PersistentDataType.STRING),
                data.get(ARRIVE, PersistentDataType.STRING), data.get(WAIT, PersistentDataType.STRING),
                data.get(TICK, PersistentDataType.STRING));
    }

    public void write(Player player, TeleportLookChoice choice) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        put(data, DEPART, choice.depart());
        put(data, ARRIVE, choice.arrive());
        put(data, WAIT, choice.waitParticle());
        put(data, TICK, choice.tick());
    }

    private static void put(PersistentDataContainer data, NamespacedKey key, String value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.STRING, value);
        }
    }
}
