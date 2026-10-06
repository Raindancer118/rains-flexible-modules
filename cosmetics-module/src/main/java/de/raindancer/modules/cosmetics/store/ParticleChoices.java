package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * A player's particle, kept in their own persistent data — saved with the player file, no file of this
 * module's to lose or migrate. Fixed {@code rainscosmetics:} keys rather than plugin-built ones, so the
 * standalone plugin and a host bundling the module read each other's.
 *
 * <p>Only touch a player on their own thread.
 */
public final class ParticleChoices {

    private static final NamespacedKey PARTICLE = key("particle");
    private static final NamespacedKey SHAPE = key("particle-shape");
    private static final NamespacedKey COLOUR = key("particle-colour");

    private static NamespacedKey key(String name) {
        NamespacedKey key = NamespacedKey.fromString("rainscosmetics:" + name);
        if (key == null) {
            throw new IllegalStateException("rainscosmetics:" + name + " is not a valid key");
        }
        return key;
    }

    public ParticleChoice read(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String particle = data.get(PARTICLE, PersistentDataType.STRING);
        if (particle == null || particle.isBlank()) {
            return ParticleChoice.NONE;
        }
        ParticleShape shape = ParticleShape.of(data.get(SHAPE, PersistentDataType.STRING)).orElse(ParticleShape.AMBIENT);
        return new ParticleChoice(particle, shape, data.get(COLOUR, PersistentDataType.INTEGER));
    }

    public void write(Player player, ParticleChoice choice) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (choice.isNone()) {
            data.remove(PARTICLE);
            data.remove(SHAPE);
            data.remove(COLOUR);
            return;
        }
        data.set(PARTICLE, PersistentDataType.STRING, choice.particle());
        data.set(SHAPE, PersistentDataType.STRING, choice.shape().key());
        if (choice.colour() == null) {
            data.remove(COLOUR);
        } else {
            data.set(COLOUR, PersistentDataType.INTEGER, choice.colour());
        }
    }
}
