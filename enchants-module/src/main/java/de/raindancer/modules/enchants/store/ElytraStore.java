package de.raindancer.modules.enchants.store;

import de.raindancer.modules.enchants.model.BoostCharges;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Boost charges and the Launch cooldown, on the player in {@link AirStore#NAMESPACE} so they follow across the network. */
public final class ElytraStore {

    private static final NamespacedKey CHARGES = new NamespacedKey(AirStore.NAMESPACE, "boost_charges");
    private static final NamespacedKey NEXT = new NamespacedKey(AirStore.NAMESPACE, "boost_next");
    private static final NamespacedKey LAUNCH_READY = new NamespacedKey(AirStore.NAMESPACE, "launch_ready");

    public BoostCharges charges(Player player, int capacity) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        Integer charges = data.get(CHARGES, PersistentDataType.INTEGER);
        return charges == null ? BoostCharges.full(capacity)
                : BoostCharges.of(charges, data.getOrDefault(NEXT, PersistentDataType.LONG, 0L));
    }

    public void save(Player player, BoostCharges charges) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(CHARGES, PersistentDataType.INTEGER, charges.charges());
        data.set(NEXT, PersistentDataType.LONG, charges.nextMillis());
    }

    /** When, on the wall clock, Launch may be used again; 0 for now. */
    public long launchReady(Player player) {
        return player.getPersistentDataContainer().getOrDefault(LAUNCH_READY, PersistentDataType.LONG, 0L);
    }

    public void launchReady(Player player, long at) {
        player.getPersistentDataContainer().set(LAUNCH_READY, PersistentDataType.LONG, at);
    }
}
