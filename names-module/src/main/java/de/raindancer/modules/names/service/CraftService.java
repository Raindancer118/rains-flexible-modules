package de.raindancer.modules.names.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.names.NamesSettings;
import de.raindancer.modules.names.model.Craft;
import de.raindancer.modules.names.model.Ingredient;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.rules.CraftRule;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.StyleTags;
import de.raindancer.modules.names.util.Naming;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The crafting grid: what a layout would make, and handing it over when somebody takes it.
 *
 * <h2>Why the ingredients are consumed by hand</h2>
 * These layouts are not Bukkit recipes — {@link CraftRule} explains why they cannot be. Vanilla will
 * therefore happily let the result be put in the slot and then not charge for it, because as far as the
 * server is concerned no recipe was crafted. So everything vanilla would have done is done here: the
 * result goes to the cursor or to the inventory, and the grid is decremented.
 *
 * <h2>Why the grid is read twice</h2>
 * Once for the preview, and again on the click that takes it. The window is live — a hopper, another
 * plugin or the player's own second click can change the matrix between the two — so the click resolves
 * the grid from scratch rather than trusting what the preview decided. A craft resolved against a grid
 * that no longer exists is how somebody gets an item nobody was charged for.
 */
public final class CraftService implements INamesService {

    private final Plugin plugin;
    private final Supplier<Palette> palette;
    private volatile NamesSettings settings;

    public CraftService(Plugin plugin, Supplier<Palette> palette, NamesSettings settings) {
        this.plugin = plugin;
        this.palette = palette;
        this.settings = settings;
    }

    @Override
    public void settings(NamesSettings fresh) {
        this.settings = fresh;
    }

    /** The rule as it is now: a fresh palette and the ceiling from the current settings. */
    public CraftRule rule() {
        return new CraftRule(palette.get(), settings.stops());
    }

    /**
     * What this grid means, or {@code null} for the overwhelming majority of grids, which mean nothing
     * to this module.
     */
    public Craft resolve(ItemStack[] matrix) {
        List<Ingredient> filled = new ArrayList<>();
        for (int slot = 0; slot < matrix.length; slot++) {
            ItemStack item = matrix[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            filled.add(new Ingredient(slot, item.getType(), hasCustomName(item),
                    StyleTags.read(item), item.getAmount()));
        }
        return filled.isEmpty() ? null : rule().resolve(filled);
    }

    /** The item a craft produces, or {@code null} if the grid has changed under us. */
    public ItemStack build(ItemStack[] matrix, Craft craft) {
        Palette current = palette.get();
        return switch (craft) {
            case Craft.StyleTag styleTag -> at(matrix, styleTag.tagSlot())
                    .map(tag -> StyleTags.styled(tag.asOne(), styleTag.style(), current))
                    .orElse(null);

            case Craft.CopyTag copy -> at(matrix, copy.styledSlot())
                    .map(tag -> {
                        ItemStack pair = StyleTags.styled(tag.asOne(), copy.style(), current);
                        pair.setAmount(2);
                        return pair;
                    })
                    .orElse(null);

            // Built from the blank, not from a coloured one: the blank is the tag with no name, no lore
            // and no glint on it, so the gradient tag comes out clean rather than wearing whatever the
            // first stop happened to be carrying.
            case Craft.GradientTag gradient -> at(matrix, gradient.plainSlot())
                    .map(tag -> StyleTags.styled(tag.asOne(), gradient.style(), current))
                    .orElse(null);

            case Craft.ApplyToItem apply -> at(matrix, apply.itemSlot())
                    .map(target -> painted(target, apply.style()))
                    .orElse(null);
        };
    }

    /**
     * Shows what the grid would make.
     *
     * @return whether a result was put in the slot
     */
    public boolean preview(CraftingInventory inventory) {
        Craft craft = resolve(inventory.getMatrix());
        if (craft == null) {
            return false;
        }
        ItemStack result = build(inventory.getMatrix(), craft);
        if (result == null) {
            return false;
        }
        inventory.setResult(StyleTags.marked(result));
        return true;
    }

    /**
     * Hands the result over and charges for it.
     *
     * <p>Only for a click this module put a result there for — the caller has already checked the mark,
     * because a vanilla result must go on behaving exactly as it always has.
     *
     * @return whether anything was handed over. {@code false} leaves the grid untouched, which is the
     *         only safe answer to a click nothing here knows how to serve
     */
    public boolean take(Player player, CraftingInventory inventory, ClickType click,
                        ItemStack onCursor) {
        Craft craft = resolve(inventory.getMatrix());
        if (craft == null) {
            return false;
        }
        ItemStack result = build(inventory.getMatrix(), craft);
        if (result == null) {
            return false;
        }
        result = StyleTags.unmarked(result);

        switch (click) {
            case LEFT, RIGHT -> {
                // Only onto an empty cursor. The result is a uniquely named item, so it would never
                // stack with what is already being carried anyway.
                if (onCursor != null && !onCursor.isEmpty()) {
                    return false;
                }
                player.getOpenInventory().setCursor(result);
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                // A free slot, checked first: addItem would otherwise put half of a two-tag result away
                // and hand back the rest, and there would be nothing sensible to do with it.
                if (player.getInventory().firstEmpty() == -1) {
                    return false;
                }
                player.getInventory().addItem(result);
            }
            default -> {
                return false;
            }
        }

        consume(inventory, craft);
        // The grid is read back by the client after the event returns, so the refresh has to happen
        // after this tick or the player sees the old result until they touch the window again.
        // entityLater rather than entity, and the tick is said out loud: the point here is to let the
        // server finish applying the click before anything reads it back, which is exactly the case
        // Core's Scheduling says not to leave to "next tick anyway". Through Scheduling either way,
        // because on Folia the window belongs to the player's own region thread.
        Scheduling.entityLater(plugin, player, 1L, player::updateInventory);
        return true;
    }

    /** The whole stack, renamed. Sixty-four ingots named in one go, the way an anvil does it. */
    private static ItemStack painted(ItemStack target, NameStyle style) {
        ItemStack copy = target.clone();
        copy.editMeta(meta -> meta.displayName(Naming.apply(currentName(meta, target), style)));
        return copy;
    }

    /**
     * The name to repaint: the item's own if it has been named, its type's name if it has not.
     *
     * <p>Falling back to the type's name is what makes "dye my sword red" work without an anvil first.
     * The result is a custom name that happens to read "Diamond Sword", which is exactly what a player
     * who put a red tag next to a plain sword was asking for.
     */
    private static Component currentName(ItemMeta meta, ItemStack item) {
        return meta.hasDisplayName() ? meta.displayName() : Component.translatable(item.getType());
    }

    private static Optional<ItemStack> at(ItemStack[] matrix, int slot) {
        if (slot < 0 || slot >= matrix.length) {
            return Optional.empty();
        }
        ItemStack item = matrix[slot];
        return item == null || item.getType().isAir() ? Optional.empty() : Optional.of(item);
    }

    private static boolean hasCustomName(ItemStack item) {
        return item.hasItemMeta() && item.getItemMeta().hasDisplayName();
    }

    /** Charges for the craft: one off each tag and reagent, and the whole stack of whatever was painted. */
    private static void consume(CraftingInventory inventory, Craft craft) {
        ItemStack[] matrix = inventory.getMatrix();
        for (int slot : craft.takeOne()) {
            if (slot >= 0 && slot < matrix.length && matrix[slot] != null) {
                matrix[slot].setAmount(matrix[slot].getAmount() - 1);
                if (matrix[slot].getAmount() <= 0) {
                    matrix[slot] = null;
                }
            }
        }
        int whole = craft.takeAll();
        if (whole >= 0 && whole < matrix.length) {
            matrix[whole] = null;
        }
        inventory.setMatrix(matrix);
        inventory.setResult(null);
    }

    @Override
    public String describe() {
        return "reads a crafting grid, builds what it makes, and charges for it";
    }
}
