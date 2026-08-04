package de.raindancer.modules.names.store;

import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.util.Naming;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading and writing a {@link NameStyle} on a name tag.
 *
 * <p>In {@code store} because a styled tag <em>is</em> where this module keeps its data: there is no
 * registry and no file of tags, the item is the record. Everything that loads or writes one is here.
 *
 * <h2>A styled tag is still a name tag</h2>
 * Its own display name is left alone, on purpose. That is what keeps it working as a name tag: an anvil
 * still names it, and naming a mob with it still names the mob (in colour — see {@code MobNameService}).
 * The style shows up in the lore instead, where it can be seen without being in the way.
 *
 * <h2>Why the keys are not built from the plugin</h2>
 * {@code new NamespacedKey(plugin, "colours")} would namespace the data under whichever plugin wrote it,
 * and this module ships two ways — as {@code RainsColouredNames}, its own plugin, and inside a host such
 * as {@code RainsSMPCore}. A tag dyed on a server running one would then be a plain name tag on a server
 * running the other. The fixed {@code colourednames:} namespace is the only way those builds can read
 * each other's items, and it is the namespace the standalone plugin has always used, so every tag
 * already dyed on an upgrading server keeps its colour. Players carry items between servers; changing
 * this would strip every one of them.
 */
public final class StyleTags {

    private static final NamespacedKey COLOURS = key("colours");
    private static final NamespacedKey DECORATIONS = key("decorations");

    /**
     * Marks the item sitting in a crafting grid's result slot as one this module put there.
     *
     * <p>Without it there is no way to tell our result from a vanilla one on the click that takes it,
     * and taking a vanilla result must go on working exactly as it always has.
     */
    private static final NamespacedKey PREVIEW = key("preview");

    private StyleTags() {
    }

    /**
     * The namespace every styled tag in the world already carries.
     *
     * <p>A constant rather than a literal at each use, because the two uses below are the key and the
     * message naming it, and a namespace changed in one of them is a change that still reads right.
     */
    static final String NAMESPACE = "colourednames";

    private static NamespacedKey key(String name) {
        NamespacedKey key = NamespacedKey.fromString(NAMESPACE + ":" + name);
        if (key == null) {
            throw new IllegalStateException(NAMESPACE + ":" + name + " is not a valid key.");
        }
        return key;
    }

    // ------------------------------------------------------------------ reading

    /** The style on {@code item}, or {@link NameStyle#NONE} for anything that carries none. */
    public static NameStyle read(ItemStack item) {
        if (item == null || item.getType() != Material.NAME_TAG || !item.hasItemMeta()) {
            return NameStyle.NONE;
        }
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return NameStyle.decode(
                data.getOrDefault(COLOURS, PersistentDataType.STRING, ""),
                data.getOrDefault(DECORATIONS, PersistentDataType.STRING, ""));
    }

    public static boolean isPreview(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(PREVIEW, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ writing

    /**
     * A copy of {@code tag} carrying {@code style}, with its lore rewritten to show it.
     *
     * <p>An empty style strips the tag back to an ordinary name tag rather than storing "nothing", so a
     * washed tag stacks with the ones in the shop chest again.
     */
    public static ItemStack styled(ItemStack tag, NameStyle style, Palette palette) {
        ItemStack copy = tag.clone();
        copy.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            if (style.isEmpty()) {
                data.remove(COLOURS);
                data.remove(DECORATIONS);
                meta.lore(null);
                meta.setEnchantmentGlintOverride(null);
                return;
            }
            data.set(COLOURS, PersistentDataType.STRING, style.encodeColours());
            data.set(DECORATIONS, PersistentDataType.STRING, style.encodeDecorations());
            meta.lore(lore(meta, style, palette));
            // The glint is the only thing that distinguishes a styled tag in a hotbar at a glance.
            meta.setEnchantmentGlintOverride(true);
        });
        return copy;
    }

    /** Marks a result item, so the click that takes it can be recognised as ours. */
    public static ItemStack marked(ItemStack result) {
        ItemStack copy = result.clone();
        copy.editMeta(meta -> meta.getPersistentDataContainer()
                .set(PREVIEW, PersistentDataType.BYTE, (byte) 1));
        return copy;
    }

    /** Takes the mark off again, on the way into the player's hands. */
    public static ItemStack unmarked(ItemStack result) {
        ItemStack copy = result.clone();
        copy.editMeta(meta -> meta.getPersistentDataContainer().remove(PREVIEW));
        return copy;
    }

    // ------------------------------------------------------------------ the lore

    /**
     * What a styled tag says about itself: the style, painted in itself, and then in words.
     *
     * <p>The sample is the tag's own name when it has one, because at that point the preview is showing
     * the player the exact thing the mob or the item is going to end up wearing.
     */
    private static List<Component> lore(ItemMeta meta, NameStyle style, Palette palette) {
        String sample = meta.hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                : Naming.SAMPLE;

        List<Component> lore = new ArrayList<>();
        lore.add(Naming.styled(sample, style));
        lore.add(Component.text(words(style, palette), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Craft with an item to paint its name.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    /**
     * "pink → light blue, bold" — the style spelled out, for anyone who cannot tell two blues apart.
     *
     * <p>The arrow rather than a comma between colours, because two colours on a tag are not two things
     * it is; they are where the gradient starts and where it ends, and the reading order is the one
     * thing about a gradient a player has to get right.
     */
    private static String words(NameStyle style, Palette palette) {
        List<String> parts = new ArrayList<>();
        if (!style.colours().isEmpty()) {
            parts.add(style.colours().stream().map(palette::nameOf)
                    .reduce((a, b) -> a + " → " + b).orElse(""));
        }
        style.decorations().forEach(decoration ->
                parts.add(new Reagent.Decoration(decoration).describe()));
        return String.join(", ", parts);
    }
}
