package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.rules.Volley;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Volley: a thrown trident brings others along, fanned out like Multishot. The others are copies that cannot be
 * picked up, never come back (Loyalty and Riptide taken off them) and are gone soon after they land — nothing is
 * duplicated. Everything else on the trident (Impaling, Thor, Heat-seeking …) they carry too.
 */
public final class VolleyService {

    private static final NamespacedKey COPY = new NamespacedKey("rainsenchants", "volley_copy");
    /** Ticks a copy lasts at most, flying or lying. */
    private static final long COPY_TICKS = 100;
    /** Ticks a copy lies where it landed before it goes. */
    private static final long LYING_TICKS = 20;

    private final Plugin plugin;
    private final EnchantAdvancements advancements;
    /** Set while this throws the copies: their own launches are not thrown again. */
    private final ThreadLocal<Boolean> throwing = ThreadLocal.withInitial(() -> false);

    public VolleyService(Plugin plugin, EnchantAdvancements advancements) {
        this.plugin = plugin;
        this.advancements = advancements;
    }

    public void launched(Player thrower, Trident trident) {
        if (throwing.get() || trident.getPersistentDataContainer().has(COPY)) {
            return;
        }
        int level = CustomEnchantments.levelOn(trident.getItemStack(), RainsEnchants.VOLLEY);
        if (level < 1) {
            return;
        }
        Vector velocity = trident.getVelocity();
        double speed = velocity.length();
        ItemStack copyItem = trident.getItemStack().clone();
        copyItem.setAmount(1);
        copyItem.removeEnchantment(Enchantment.LOYALTY);
        copyItem.removeEnchantment(Enchantment.RIPTIDE);
        throwing.set(true);
        try {
            for (double[] direction : Volley.directions(new double[]{velocity.getX(), velocity.getY(), velocity.getZ()},
                    level)) {
                Trident copy = thrower.launchProjectile(Trident.class,
                        new Vector(direction[0], direction[1], direction[2]).multiply(speed));
                copy.getPersistentDataContainer().set(COPY, PersistentDataType.BYTE, (byte) 1);
                copy.setItemStack(copyItem.clone());
                copy.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                Scheduling.entityLater(plugin, copy, COPY_TICKS, copy::remove);
            }
        } finally {
            throwing.set(false);
        }
        advancements.used(thrower.getUniqueId(), RainsEnchants.VOLLEY);
    }

    /** A copy that landed or struck lies a moment, then is gone. */
    public void hit(Trident trident) {
        if (trident.getPersistentDataContainer().has(COPY)) {
            Scheduling.entityLater(plugin, trident, LYING_TICKS, trident::remove);
        }
    }
}
