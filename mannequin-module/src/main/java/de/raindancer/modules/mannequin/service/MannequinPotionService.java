package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading a consumable item's effects, without ever eating or drinking it through any inventory —
 * and recognising the handful of items a mannequin is allowed to consume when dropped at its feet.
 *
 * <h2>Two sources of effects, because potions and food are shaped differently</h2>
 * A potion's effects live on its {@code PotionMeta} — {@code getAllEffects()} already mixes the
 * base type's own effects with any custom ones, so there is nothing to reimplement for those. A
 * golden apple's effects are not meta at all: they are the item's {@code
 * DataComponentTypes.CONSUMABLE} component, the very data vanilla itself reads to decide what
 * eating the item grants. Reading that component rather than hard-coding "a golden apple gives
 * regeneration II" is what keeps this correct if a resource pack or a datapack changes what the
 * item actually does — the same "read the real thing, never guess" rule the durability and
 * redstone math already follow.
 */
public final class MannequinPotionService implements IMannequinService {

    /** What a mannequin will pick up and consume — a curated whitelist, not "anything droppable". */
    private static final List<Material> CONSUMABLE_MATERIALS = List.of(
            Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION,
            Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE);

    private volatile MannequinSettings settings;

    public MannequinPotionService(MannequinSettings settings) {
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    public static boolean isRecognised(Material material) {
        return CONSUMABLE_MATERIALS.contains(material);
    }

    /** Every effect this item stack grants when consumed. Empty for anything not recognised. */
    public List<PotionEffect> consume(ItemStack item) {
        if (item == null || !isRecognised(item.getType())) {
            return List.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof PotionMeta potionMeta) {
            return List.copyOf(potionMeta.getAllEffects());
        }
        if (!item.hasData(DataComponentTypes.CONSUMABLE)) {
            return List.of();
        }
        Consumable consumable = item.getData(DataComponentTypes.CONSUMABLE);
        List<PotionEffect> effects = new ArrayList<>();
        for (ConsumeEffect effect : consumable.consumeEffects()) {
            if (effect instanceof ConsumeEffect.ApplyStatusEffects applied) {
                effects.addAll(applied.effects());
            }
        }
        return List.copyOf(effects);
    }

    /**
     * Applies whatever this item grants to the mannequin and consumes exactly one from the stack —
     * never carried, never left behind as a free item either.
     *
     * <h2>Why both the pickup listener and a periodic sweep call this</h2>
     * A mannequin is an inert, static entity rather than a pathing mob — {@code
     * setCanPickupItems(true)} governs whether it is <em>allowed</em> to pick something up, not
     * whether it walks over to or reaches for an item that never overlaps it, and a Mannequin has
     * no AI goal that would make it do either. Relying on {@code EntityPickupItemEvent} alone left
     * every dropped potion and apple simply sitting there, untouched, which is why {@code
     * MannequinModule}'s periodic upkeep also sweeps for nearby items directly and calls this the
     * same way. Kept as one method rather than two copies of "read effects, apply them, consume
     * one" so a future third trigger has exactly one place to call.
     *
     * @return whether anything was actually consumed
     */
    public boolean tryConsume(LivingEntity mannequin, Item itemEntity) {
        if (mannequin == null || itemEntity == null) {
            return false;
        }
        ItemStack stack = itemEntity.getItemStack();
        if (!isRecognised(stack.getType())) {
            return false;
        }
        for (PotionEffect effect : consume(stack)) {
            mannequin.addPotionEffect(effect);
        }
        int amount = stack.getAmount();
        if (amount <= 1) {
            itemEntity.remove();
        } else {
            stack.setAmount(amount - 1);
            itemEntity.setItemStack(stack);
        }
        return true;
    }
}
