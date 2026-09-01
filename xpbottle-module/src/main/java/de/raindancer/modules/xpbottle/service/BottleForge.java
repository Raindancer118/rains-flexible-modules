package de.raindancer.modules.xpbottle.service;

import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.store.BottleTags;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the actual bottles, and keeps what a player reads on one in step with what is written on it.
 *
 * <h2>Why a siphon bottle is a potion and not a bottle o' enchanting</h2>
 * Because of what "hold it down" costs. Holding right click only does anything for an item the
 * client believes is being <em>consumed</em>, and that belief comes from the {@code consumable}
 * component. A potion already goes through vanilla's consumable path, so overriding the component on
 * one is a change to a number vanilla was going to read anyway. A bottle o' enchanting does not: its
 * item class throws the bottle instead, and a {@code consumable} component on one is quietly
 * ignored — a siphon that can never be held down, with nothing anywhere saying why.
 *
 * <p>The consume itself never completes: {@link #NEVER_FINISHES} seconds is longer than anybody
 * holds a mouse button, and {@code BottleUseListener} cancels the consume event as well. The
 * animation is the whole point of the component; finishing it is not wanted at all.
 *
 * <h2>Why the colour carries the fill level</h2>
 * A potion's colour is the one part of a bottle visible from across a room and in a hotbar slot the
 * holder is not looking at. Empty is the pale green of a bottle nobody has used; full is the deep
 * lime of experience itself. In between is the mix, so a bottle fills up in the hand.
 */
public final class BottleForge implements IXpBottleService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Long enough that no held mouse button ever reaches the end of it. */
    private static final float NEVER_FINISHES = 3600.0f;

    /** Roman numerals for the tiers a sane server has; past that, the plain number. */
    private static final String[] NUMERALS = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII",
            "IX", "X"};

    private static final Color EMPTY_TINT = Color.fromRGB(0x9BD3A0);
    private static final Color FULL_TINT = Color.fromRGB(0x4CE016);

    private volatile XpBottleSettings settings;

    public BottleForge(XpBottleSettings settings) {
        this.settings = settings == null ? XpBottleSettings.DEFAULTS : settings;
    }

    @Override
    public void settings(XpBottleSettings updated) {
        this.settings = updated == null ? XpBottleSettings.DEFAULTS : updated;
    }

    /** How this tier is written in a sentence — {@code III}, or {@code 12} past the numerals. */
    public static String numeral(int tier) {
        return tier > 0 && tier < NUMERALS.length ? NUMERALS[tier] : String.valueOf(tier);
    }

    /** An empty siphon bottle of that tier. */
    public ItemStack siphon(int tier) {
        XpBottleSettings live = settings;
        int level = Math.max(1, Math.min(tier, live.highestTierClamped()));
        return stackFor(new Bottle(level, 0, live.capacityFor(level)));
    }

    /** An ordinary, untagged glass bottle — what pouring a plain one out leaves behind. */
    public ItemStack emptyGlass() {
        return new ItemStack(Material.GLASS_BOTTLE);
    }

    /**
     * The stack a bottle in this state is.
     *
     * <p>A plain bottle changes material as it fills: nothing in it is a glass bottle, something in
     * it is a bottle o' enchanting, because those are what a player already reads as the two states.
     * A siphon keeps its potion shape throughout and shows its level in its colour instead — it is
     * an item somebody was given, and one that turned into a different item when it emptied would be
     * one they think they have lost.
     */
    public ItemStack stackFor(Bottle bottle) {
        if (bottle == null) {
            return emptyGlass();
        }
        if (bottle.isPlain() && bottle.isEmpty()) {
            return emptyGlass();
        }
        ItemStack stack = new ItemStack(
                bottle.isPlain() ? Material.EXPERIENCE_BOTTLE : Material.POTION);
        dress(stack, bottle);
        return stack;
    }

    /**
     * Writes a bottle's state onto a stack that already exists — its tags, its name, its lore and,
     * for a siphon, its colour.
     *
     * <p>Used while a siphon is drawing, where the stack is the one in the player's hand and
     * replacing it would interrupt the very use animation that is driving the draw.
     */
    public void dress(ItemStack stack, Bottle bottle) {
        if (stack == null || bottle == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(nameOf(bottle));
        meta.lore(loreOf(bottle));
        if (bottle.mayVacuum()) {
            // The shimmer, and the flag that stops "Unbreaking I" appearing under the name.
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        } else {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }
        if (meta instanceof PotionMeta potion) {
            potion.setColor(tintFor(bottle));
        }
        stack.setItemMeta(meta);

        if (bottle.mayVacuum()) {
            stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                    .consumeSeconds(NEVER_FINISHES)
                    .animation(ItemUseAnimation.DRINK)
                    .hasConsumeParticles(false)
                    .sound(Key.key("minecraft:entity.experience_orb.pickup")));
        }
        BottleTags.stamp(stack, bottle);
    }

    /**
     * What a player reads this bottle called.
     *
     * <p>A {@link Component} rather than a {@code String} of markup, and not because the caller
     * cares: markup handed around as a value is markup something eventually puts through a
     * placeholder, where it is escaped and the player reads the tags. Building it here is the only
     * place that cannot happen.
     */
    public Component nameOf(Bottle bottle) {
        if (bottle.mayVacuum()) {
            return line("<gradient:#7CFC00:#00CED1>Siphon Bottle " + numeral(bottle.level())
                    + "</gradient>");
        }
        return line("<green>Bottled Experience");
    }

    /** What a player reads under the name. */
    public List<Component> loreOf(Bottle bottle) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>Holding <white>" + bottle.stored() + "</white> of <white>"
                + bottle.capacity() + "</white> points"));
        if (bottle.mayVacuum()) {
            int reach = settings.reachFor(bottle.level());
            lore.add(line("<dark_gray>Reaches <gray>" + reach + "</gray> blocks"));
            lore.add(Component.empty());
            lore.add(line("<yellow>Hold right click<gray> to draw in loose"));
            lore.add(line("<gray>experience, and your own when there"));
            lore.add(line("<gray>is none on the ground."));
            lore.add(line("<yellow>Sneak and right click<gray> to pour it back."));
        } else {
            lore.add(Component.empty());
            lore.add(line("<yellow>Right click<gray> to pour it back into yourself."));
        }
        return lore;
    }

    /** Pale when empty, the green of experience when full, the mix in between. */
    private Color tintFor(Bottle bottle) {
        if (bottle.capacity() <= 0) {
            return EMPTY_TINT;
        }
        double filled = Math.max(0.0, Math.min(1.0, bottle.stored() / (double) bottle.capacity()));
        return Color.fromRGB(
                mix(EMPTY_TINT.getRed(), FULL_TINT.getRed(), filled),
                mix(EMPTY_TINT.getGreen(), FULL_TINT.getGreen(), filled),
                mix(EMPTY_TINT.getBlue(), FULL_TINT.getBlue(), filled));
    }

    private static int mix(int from, int to, double amount) {
        return (int) Math.round(from + (to - from) * amount);
    }

    private static Component line(String miniMessage) {
        // Italics off explicitly: Minecraft draws a custom name and custom lore slanted otherwise.
        return MINI.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }
}
