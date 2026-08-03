package de.raindancer.modules.claims.util;

import de.raindancer.core.platform.util.Wrapping;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Small fluent builder for GUI icons. */
public final class Items {

    private final ItemStack stack;
    private final List<Component> lore = new ArrayList<>();

    private Items(Material material, int amount) {
        this.stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
    }

    private Items(ItemStack existing) {
        this.stack = existing.clone();
        List<Component> current = this.stack.lore();
        if (current != null) {
            lore.addAll(current);
        }
    }

    public static Items of(Material material) {
        return new Items(material, 1);
    }

    public static Items of(Material material, int amount) {
        return new Items(material, amount);
    }

    public static Items copyOf(ItemStack existing) {
        return new Items(existing);
    }

    public Items name(String miniMessage) {
        stack.editMeta(meta -> meta.displayName(de.raindancer.core.ui.menu.Icons.name(miniMessage)));
        return this;
    }

    public Items name(Component component) {
        stack.editMeta(meta -> meta.displayName(component.decoration(TextDecoration.ITALIC, false)));
        return this;
    }

    public Items lore(String... lines) {
        for (String line : lines) {
            lore.add(de.raindancer.core.ui.menu.Icons.loreLine(line));
        }
        return this;
    }

    public Items lore(Component line) {
        lore.add(line.decoration(TextDecoration.ITALIC, false));
        return this;
    }

    public Items loreRaw(List<Component> lines) {
        for (Component line : lines) {
            lore.add(line.decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    public Items blank() {
        lore.add(Component.empty());
        return this;
    }

    /** Wraps a long sentence into lore lines of roughly {@code width} characters. */
    public Items wrapped(String text, int width, NamedTextColor color) {
        for (String line : Wrapping.wrap(text, width)) {
            lore.add(Component.text(line, color).decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    public Items amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        return this;
    }

    public Items glint(boolean enabled) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(enabled));
        return this;
    }

    public Items hideAttributes() {
        stack.editMeta(meta -> meta.addItemFlags(ItemFlag.values()));
        return this;
    }

    public Items skullOf(UUID owner) {
        stack.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(owner)));
        return this;
    }

    public Items tag(NamespacedKey key, String value) {
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value));
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) {
            stack.lore(new ArrayList<>(lore));
        }
        return stack;
    }

    // ------------------------------------------------------------ common icons

    public static ItemStack filler(Material material) {
        return of(material).name("<gray>").build();
    }

    public static ItemStack back(String target) {
        return of(Material.ARROW).name("<yellow>Back").lore("to " + target).build();
    }

    public static ItemStack close() {
        return of(Material.BARRIER).name("<red>Close").build();
    }

    /** Chrome: jumps to the top of the chain the player came down, however deep they went. */
    public static ItemStack home(String target) {
        return of(Material.COMPASS).name("<yellow>Home").lore("back to " + target).build();
    }

    /** Chrome: what this page is for, answered on the page itself. */
    public static ItemStack help(List<String> lines) {
        Items builder = of(Material.WRITTEN_BOOK).name("<yellow>What is this page?");
        for (String line : lines) {
            builder.lore("<gray>" + line);
        }
        return builder.build();
    }

    /** Toolbar: the search box of a long list. */
    public static ItemStack search(String query) {
        return of(query == null ? Material.SPYGLASS : Material.WRITABLE_BOOK)
                .name("<yellow>Search")
                .lore(query == null
                        ? new String[]{"<gray>Type a name and only the matches stay."}
                        : new String[]{"<white>Showing: <yellow>" + query,
                                "<yellow>Right-click <gray>show everything again"})
                .build();
    }

    /**
     * A button the viewer may look at but not use.
     * <p>
     * Keeps the material, so it is recognisably the same thing they saw somewhere they <em>were</em>
     * allowed to press it, and says whose it is instead. Better than hiding it: a player who cannot see
     * the button asks why the feature is missing, one who sees it greyed asks the right person.
     */
    public static ItemStack locked(ItemStack original, String reason) {
        Items builder = copyOf(original);
        ItemMeta meta = original.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            builder.name("<dark_gray>" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
        }
        return builder.blank().lore("<dark_gray>✖ " + reason).build();
    }

    public static ItemStack toggle(boolean value, String label, String... lore) {
        Items builder = of(value ? Material.LIME_DYE : Material.GRAY_DYE)
                .name((value ? "<green>" : "<gray>") + label);
        builder.lore(lore);
        builder.blank();
        builder.lore(value ? "<green>● enabled" : "<dark_gray>○ disabled", "<yellow>Click to toggle");
        return builder.build();
    }

    /**
     * A toggle the server has taken over: it shows the state that is in force and says so, rather than
     * pretending the click will do something.
     */
    public static ItemStack forcedToggle(boolean value, String label, String... lore) {
        Items builder = of(value ? Material.LIME_DYE : Material.GRAY_DYE)
                .name((value ? "<gold>" : "<dark_gray>") + label);
        builder.lore(lore);
        builder.blank();
        builder.lore(value ? "<gold>● forced on by the server" : "<dark_gray>○ switched off by the server",
                "<dark_gray>This is not yours to change");
        return builder.build();
    }

    public static ItemStack pageArrow(boolean forward, int page) {
        return of(Material.SPECTRAL_ARROW)
                .name(forward ? "<yellow>Next page" : "<yellow>Previous page")
                .lore("Page " + page)
                .build();
    }

    public static boolean hasTag(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public static String tagValue(ItemStack stack, NamespacedKey key) {
        if (stack == null) {
            return null;
        }
        return stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** Copies the item meta of {@code source} onto a fresh stack of the same type. */
    public static ItemStack describe(ItemStack source, List<Component> extraLore) {
        ItemStack copy = source.clone();
        copy.editMeta(meta -> {
            List<Component> combined = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) {
                combined.addAll(existing);
            }
            combined.addAll(extraLore);
            meta.lore(combined);
        });
        return copy;
    }

    public static List<Component> loreOf(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return List.of();
        }
        return meta.lore();
    }

    /** @deprecated call {@link Wrapping#wrap(String, int)} directly. */
    @Deprecated
    public static List<String> split(String text, int width) {
        return Wrapping.wrap(text, width);
    }

    public static List<Material> woolColors() {
        return Arrays.asList(
                Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
                Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
                Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
                Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL);
    }
}
