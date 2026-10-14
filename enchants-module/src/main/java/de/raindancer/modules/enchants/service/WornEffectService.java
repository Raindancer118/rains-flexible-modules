package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.modules.enchants.EnchantAdvancements;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Night Vision on a helmet: an endless, quiet effect while it is worn. The effect is recognisable as
 * ours by how it is made — endless, no particles, no icon — so one from a potion or a command is never
 * touched, and one left behind by a removed helmet or a stopped module is cleaned up.
 */
public final class NightVisionService {

    private final EnchantAdvancements advancements;

    public NightVisionService(EnchantAdvancements advancements) {
        this.advancements = advancements;
    }

    public void sync(Player player) {
        boolean wanted = CustomEnchantments.levelOn(player.getInventory().getHelmet(), RainsEnchants.NIGHT_VISION) > 0;
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (wanted && current == null) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0,
                    true, false, false));
            advancements.used(player.getUniqueId(), RainsEnchants.NIGHT_VISION);
        } else if (!wanted && current != null && isOurs(current)) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    /** Takes the effect back, for a module that is stopping. */
    public void release(Player player) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current != null && isOurs(current)) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    static boolean isOurs(PotionEffect effect) {
        return effect.isInfinite() && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon();
    }
}
