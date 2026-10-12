package de.raindancer.modules.enchants.store;

import de.raindancer.modules.enchants.model.AirState;
import de.raindancer.modules.enchants.model.FlightBudget;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The flight charge lives on the player, so logging out is not a refill. Only what a restart must not
 * lose: the charge and the lifetime flight.
 */
public final class AirStore {

    private static final NamespacedKey CHARGE = new NamespacedKey("rainsenchants", "flight_ms");
    private static final NamespacedKey FLOWN = new NamespacedKey("rainsenchants", "flown_ms");

    public AirState load(Player player) {
        AirState state = new AirState();
        PersistentDataContainer data = player.getPersistentDataContainer();
        Long charge = data.get(CHARGE, PersistentDataType.LONG);
        if (charge != null) {
            state.budget = FlightBudget.of(charge);
        }
        state.flownMillis = data.getOrDefault(FLOWN, PersistentDataType.LONG, 0L);
        return state;
    }

    public void save(Player player, AirState state) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (state.budget.isKnown()) {
            data.set(CHARGE, PersistentDataType.LONG, state.budget.remainingMillis());
        }
        data.set(FLOWN, PersistentDataType.LONG, state.flownMillis);
    }
}
