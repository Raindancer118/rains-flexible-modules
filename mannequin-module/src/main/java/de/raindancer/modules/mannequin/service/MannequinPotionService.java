package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

import java.util.List;

/**
 * Reading a potion's effects out of the item, without ever pouring it into anybody.
 *
 * <p>{@code PotionMeta#getAllEffects()} already mixes the base potion type's own effects with any
 * custom ones on the meta — exactly "the base type's own effects plus any custom effects" this
 * module needs, so there is nothing to reimplement here beyond the null-safety.
 */
public final class MannequinPotionService implements IMannequinService {

    private volatile MannequinSettings settings;

    public MannequinPotionService(MannequinSettings settings) {
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    /** Every effect a potion stack carries. Empty for anything that is not a potion at all. */
    public List<PotionEffect> consume(ItemStack potion) {
        if (potion == null) {
            return List.of();
        }
        ItemMeta meta = potion.getItemMeta();
        if (!(meta instanceof PotionMeta potionMeta)) {
            return List.of();
        }
        return List.copyOf(potionMeta.getAllEffects());
    }
}
