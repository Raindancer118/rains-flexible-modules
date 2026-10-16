package de.raindancer.modules.ctf.service;

import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Mutator;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * What a twist does to a player's body: size, gravity, jump. Applied as attribute modifiers under one key of the
 * plugin's own, so taking them off again takes off exactly what was put on and nothing anybody else put there. The
 * twists that are rules rather than bodies (one hit, knockback, swapped bases, spleef) are the round's and the
 * listeners'.
 */
public final class MutatorService implements ICtfService {

    private final NamespacedKey key;
    private volatile CtfSettings settings;

    public MutatorService(Plugin plugin, CtfSettings settings) {
        this.key = new NamespacedKey(plugin, "mutator");
        this.settings = settings;
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    /** The changes to each attribute a twist makes, as a multiplier on its base. */
    static Map<Attribute, Double> changes(Mutator mutator) {
        return switch (mutator) {
            case LOW_GRAVITY -> Map.of(Attribute.GRAVITY, -0.6, Attribute.JUMP_STRENGTH, 0.35,
                    Attribute.SAFE_FALL_DISTANCE, 3.0);
            case TINY -> Map.of(Attribute.SCALE, -0.5);
            case GIANT -> Map.of(Attribute.SCALE, 0.5, Attribute.STEP_HEIGHT, 0.5);
            default -> Map.of();
        };
    }

    /** Their own thread. */
    public void apply(Player player, Mutator mutator) {
        remove(player);
        changes(mutator).forEach((attribute, factor) -> {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.addModifier(new AttributeModifier(key, factor,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
        });
    }

    /** Their own thread. */
    public void remove(Player player) {
        for (Attribute attribute : new Attribute[]{Attribute.GRAVITY, Attribute.JUMP_STRENGTH,
                Attribute.SAFE_FALL_DISTANCE, Attribute.SCALE, Attribute.STEP_HEIGHT}) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(key);
            }
        }
    }
}
