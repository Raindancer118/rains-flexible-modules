package de.raindancer.modules.enchants.service;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.rules.Fusion;
import de.raindancer.modules.enchants.rules.Undying;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The chestplate enchantments: totems kept in an Undying chestplate, Fusion — two chestplates in an anvil, the first
 * twice as strong — and the anvil never refusing as too expensive.
 */
public final class ArmourService {

    private static final NamespacedKey TOTEMS = new NamespacedKey("rainsenchants", "totems");
    private static final NamespacedKey FUSIONS = new NamespacedKey("rainsenchants", "fusions");
    private static final String TOTEM_LINE = "Totems kept: ";
    /** The client says "Too Expensive!" from 40 levels on whatever the server allows, so no anvil asks for more. */
    static final int MOST_LEVELS = 39;
    private static final int FUSION_LEVELS = 10;

    private final EnchantAdvancements advancements;

    public ArmourService(EnchantAdvancements advancements) {
        this.advancements = advancements;
    }

    private static int kept(ItemStack item) {
        return item.getPersistentDataContainer().getOrDefault(TOTEMS, PersistentDataType.INTEGER, 0);
    }

    public static int fusions(ItemStack item) {
        return item == null || item.isEmpty() ? 0
                : item.getPersistentDataContainer().getOrDefault(FUSIONS, PersistentDataType.INTEGER, 0);
    }

    // ------------------------------------------------------------------ Undying

    /**
     * A totem on the cursor clicked onto an Undying chestplate goes in, as many as there is room for.
     *
     * @return true if it took any, and the click is not to do anything else
     */
    public boolean keepTotems(Player player, ItemStack cursor, ItemStack clicked) {
        if (cursor == null || cursor.getType() != Material.TOTEM_OF_UNDYING || clicked == null || clicked.isEmpty()) {
            return false;
        }
        int level = CustomEnchantments.levelOn(clicked, RainsEnchants.UNDYING);
        int takes = Undying.takes(level, kept(clicked), cursor.getAmount());
        if (level < 1) {
            return false;
        }
        if (takes > 0) {
            withTotems(clicked, level, kept(clicked) + takes);
            cursor.setAmount(cursor.getAmount() - takes);
            player.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_GOLD, 1f, 1.2f);
        }
        return true;
    }

    /** Dying with no totem in hand: one kept in the chestplate is used instead, if there is one. */
    public boolean saveWithKeptTotem(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.isEmpty() || kept(chest) < 1) {
            return false;
        }
        int level = CustomEnchantments.levelOn(chest, RainsEnchants.UNDYING);
        withTotems(chest, level, kept(chest) - 1);
        player.getInventory().setChestplate(chest);
        advancements.used(player.getUniqueId(), RainsEnchants.UNDYING);
        return true;
    }

    private static void withTotems(ItemStack chest, int level, int totems) {
        chest.editMeta(meta -> {
            meta.getPersistentDataContainer().set(TOTEMS, PersistentDataType.INTEGER, totems);
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) {
                for (Component line : meta.lore()) {
                    if (!PlainTextComponentSerializer.plainText().serialize(line).startsWith(TOTEM_LINE)) {
                        lore.add(line);
                    }
                }
            }
            lore.add(Component.text(TOTEM_LINE + totems + "/" + Undying.capacity(level), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
    }

    // ------------------------------------------------------------------ Fusion and the anvil

    public void anvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        if (fusable(first) && fusable(second) && first.getType() == second.getType()) {
            ItemStack result = Fusion.next(fusions(first)).map(next -> fused(first, next)).orElse(null);
            event.setResult(result);
            if (result != null) {
                event.getView().setRepairCost(FUSION_LEVELS);
            }
        }
        event.getView().setMaximumRepairCost(Integer.MAX_VALUE);
        if (event.getResult() != null && !event.getResult().isEmpty() && event.getView().getRepairCost() > MOST_LEVELS) {
            event.getView().setRepairCost(MOST_LEVELS);
        }
    }

    private static boolean fusable(ItemStack item) {
        return item != null && !item.isEmpty() && item.getType().name().endsWith("_CHESTPLATE")
                && CustomEnchantments.levelOn(item, RainsEnchants.FUSION) > 0;
    }

    /** The first chestplate, fused {@code fusions} times: armour and toughness doubled each time. */
    private static ItemStack fused(ItemStack first, int fusions) {
        ItemStack result = first.clone();
        double factor = Fusion.factor(fusions);
        Multimap<Attribute, AttributeModifier> modifiers = LinkedHashMultimap.create();
        for (Map.Entry<Attribute, AttributeModifier> each : defaults(first).entries()) {
            AttributeModifier modifier = each.getValue();
            if (each.getKey() == Attribute.ARMOR || each.getKey() == Attribute.ARMOR_TOUGHNESS) {
                modifier = new AttributeModifier(new NamespacedKey("rainsenchants", "fusion_" + each.getKey().getKey().getKey()),
                        modifier.getAmount() * factor, modifier.getOperation(), EquipmentSlotGroup.CHEST);
            }
            modifiers.put(each.getKey(), modifier);
        }
        result.editMeta(meta -> {
            meta.setAttributeModifiers(modifiers);
            meta.getPersistentDataContainer().set(FUSIONS, PersistentDataType.INTEGER, fusions);
        });
        return result;
    }

    @SuppressWarnings("deprecation")
    private static Multimap<Attribute, AttributeModifier> defaults(ItemStack item) {
        return item.getType().getDefaultAttributeModifiers(EquipmentSlot.CHEST);
    }

    /** Told when a fused chestplate was taken out of the anvil. */
    public void fusedBy(Player player, ItemStack result) {
        advancements.fused(player.getUniqueId(), fusions(result));
    }

    /** What a player's hit on {@code wearer} is multiplied by, so the fused chestplate's extra counts half. */
    public double pvpFactor(Player wearer, double damage) {
        ItemStack chest = wearer.getInventory().getChestplate();
        int fusions = fusions(chest);
        if (fusions < 1) {
            return 1.0;
        }
        double baseArmour = 0;
        double baseToughness = 0;
        for (Map.Entry<Attribute, AttributeModifier> each : defaults(chest).entries()) {
            if (each.getKey() == Attribute.ARMOR) {
                baseArmour += each.getValue().getAmount();
            } else if (each.getKey() == Attribute.ARMOR_TOUGHNESS) {
                baseToughness += each.getValue().getAmount();
            }
        }
        double grown = Fusion.factor(fusions) - 1;
        return Fusion.pvpDamageFactor(damage, raw(wearer, Attribute.ARMOR), raw(wearer, Attribute.ARMOR_TOUGHNESS),
                baseArmour * grown, baseToughness * grown);
    }

    /** An attribute before the game's cap: its base and every amount added to it. */
    private static double raw(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return 0;
        }
        double sum = instance.getBaseValue();
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }
}
