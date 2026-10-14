package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.modules.enchants.EnchantAdvancements;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.function.Function;

/**
 * The enchantments that are an effect while worn or held: Night Vision on a helmet, Swiftness on leggings,
 * Haste on a tool in hand. Each effect is recognisable as ours by how it is made — endless, no particles,
 * no icon — so one from a potion or a command is never touched, and one left behind by gear taken off or a
 * stopped module is cleaned up.
 */
public final class WornEffectService {

    private record Worn(String enchantment, Function<PlayerInventory, ItemStack> where, PotionEffectType effect) {
    }

    private static final List<Worn> ALL = List.of(
            new Worn(RainsEnchants.NIGHT_VISION, PlayerInventory::getHelmet, PotionEffectType.NIGHT_VISION),
            new Worn(RainsEnchants.SWIFTNESS, PlayerInventory::getLeggings, PotionEffectType.SPEED),
            new Worn(RainsEnchants.HASTE, PlayerInventory::getItemInMainHand, PotionEffectType.HASTE));

    private final EnchantAdvancements advancements;

    public WornEffectService(EnchantAdvancements advancements) {
        this.advancements = advancements;
    }

    public void sync(Player player) {
        for (Worn worn : ALL) {
            int level = CustomEnchantments.levelOn(worn.where().apply(player.getInventory()), worn.enchantment());
            PotionEffect current = player.getPotionEffect(worn.effect());
            if (level > 0) {
                boolean ours = current != null && isOurs(current);
                if (current == null || (ours && current.getAmplifier() != level - 1)) {
                    player.addPotionEffect(new PotionEffect(worn.effect(), PotionEffect.INFINITE_DURATION, level - 1,
                            true, false, false));
                    advancements.used(player.getUniqueId(), worn.enchantment());
                }
            } else if (current != null && isOurs(current)) {
                player.removePotionEffect(worn.effect());
            }
        }
    }

    /** Takes the effects back, for a module that is stopping. */
    public void release(Player player) {
        for (Worn worn : ALL) {
            PotionEffect current = player.getPotionEffect(worn.effect());
            if (current != null && isOurs(current)) {
                player.removePotionEffect(worn.effect());
            }
        }
    }

    static boolean isOurs(PotionEffect effect) {
        return effect.isInfinite() && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon();
    }
}
