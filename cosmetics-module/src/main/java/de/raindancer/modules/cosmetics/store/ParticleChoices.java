package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
import de.raindancer.modules.cosmetics.model.ParticleSpeed;
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

    private final NamespacedKey particleKey;
    private final NamespacedKey shapeKey;
    private final NamespacedKey colourKey;
    private final NamespacedKey densityKey;
    private final NamespacedKey speedKey;
    private final NamespacedKey colourToKey;
    private final NamespacedKey naturalKey;

    /** The worn particle, under the keys it always had. */
    public ParticleChoices() {
        this("particle");
    }

    /** @param slot what the keys start with — {@code particle} for the worn one, {@code wings} for the wings */
    public ParticleChoices(String slot) {
        particleKey = key(slot);
        shapeKey = key(slot + "-shape");
        colourKey = key(slot + "-colour");
        densityKey = key(slot + "-density");
        speedKey = key(slot + "-speed");
        colourToKey = key(slot + "-colour-to");
        naturalKey = key(slot + "-natural");
    }

    private static NamespacedKey key(String name) {
        NamespacedKey key = NamespacedKey.fromString("rainscosmetics:" + name);
        if (key == null) {
            throw new IllegalStateException("rainscosmetics:" + name + " is not a valid key");
        }
        return key;
    }

    public ParticleChoice read(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String particle = data.get(particleKey, PersistentDataType.STRING);
        if (particle == null || particle.isBlank()) {
            return ParticleChoice.NONE;
        }
        ParticleShape shape = ParticleShape.of(data.get(shapeKey, PersistentDataType.STRING)).orElse(ParticleShape.AMBIENT);
        return new ParticleChoice(particle, shape, data.get(colourKey, PersistentDataType.INTEGER),
                ParticleDensity.of(data.get(densityKey, PersistentDataType.STRING)).orElse(null),
                ParticleSpeed.of(data.get(speedKey, PersistentDataType.STRING)).orElse(null),
                data.get(colourToKey, PersistentDataType.INTEGER),
                Boolean.TRUE.equals(data.get(naturalKey, PersistentDataType.BOOLEAN)));
    }

    public void write(Player player, ParticleChoice choice) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (choice.isNone()) {
            data.remove(particleKey);
            data.remove(shapeKey);
            data.remove(colourKey);
            data.remove(densityKey);
            data.remove(speedKey);
            data.remove(colourToKey);
            data.remove(naturalKey);
            return;
        }
        if (choice.speed() == null) {
            data.remove(speedKey);
        } else {
            data.set(speedKey, PersistentDataType.STRING, choice.speed().key());
        }
        if (choice.density() == null) {
            data.remove(densityKey);
        } else {
            data.set(densityKey, PersistentDataType.STRING, choice.density().key());
        }
        data.set(particleKey, PersistentDataType.STRING, choice.particle());
        data.set(shapeKey, PersistentDataType.STRING, choice.shape().key());
        if (choice.natural()) {
            data.set(naturalKey, PersistentDataType.BOOLEAN, true);
        } else {
            data.remove(naturalKey);
        }
        if (choice.colourTo() == null) {
            data.remove(colourToKey);
        } else {
            data.set(colourToKey, PersistentDataType.INTEGER, choice.colourTo());
        }
        if (choice.colour() == null) {
            data.remove(colourKey);
        } else {
            data.set(colourKey, PersistentDataType.INTEGER, choice.colour());
        }
    }
}
