package de.raindancer.modules.chat.screen;

import de.raindancer.core.ui.choose.ColorSwatches;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.model.ChatStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Picking a colour and decorations for your own chat messages, with a live preview of exactly what
 * everybody else will see.
 *
 * <h2>Why sixteen swatches rather than an anvil or a hex code</h2>
 * These are the entire chat colour palette a vanilla client can already show — see
 * {@link ChatStyle}'s own note on why this is not an Adventure {@code Style} with a hex colour.
 * Clicking one is instant and reversible, which a typed value is not: a swatch grid is the only
 * chooser here for the same reason {@code SoundChooser} lets you hear before you pick, not read a
 * key and guess.
 *
 * <h2>Why every click writes straight away</h2>
 * There is no separate "save" button. The preview at the top updates on every click precisely
 * because the choice already took effect — showing a preview of something not yet chosen would be
 * lying about what the next real message looks like.
 */
public final class ChatStyleMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Every legacy chat colour, in the order the WHO and RULES bands show them. */
    private static final List<NamedTextColor> WHO_COLORS = List.of(NamedTextColor.RED,
            NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN,
            NamedTextColor.DARK_GREEN, NamedTextColor.AQUA, NamedTextColor.DARK_AQUA);
    private static final List<NamedTextColor> RULES_COLORS = List.of(NamedTextColor.BLUE,
            NamedTextColor.DARK_BLUE, NamedTextColor.LIGHT_PURPLE, NamedTextColor.DARK_PURPLE,
            NamedTextColor.DARK_RED, NamedTextColor.WHITE, NamedTextColor.GRAY);
    private static final List<NamedTextColor> LAND_COLORS =
            List.of(NamedTextColor.DARK_GRAY, NamedTextColor.BLACK);

    private static final List<TextDecoration> DECORATIONS = List.of(TextDecoration.BOLD,
            TextDecoration.ITALIC, TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH);

    private final ChatServices services;

    public ChatStyleMenu(ChatServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your chat colour");
    }

    @Override
    public String breadcrumb() {
        return "Chat colour";
    }

    @Override
    protected void render() {
        ChatStyle style = services.styles().styleOf(viewer.getUniqueId());

        band(MenuLayout.WHO, 4, Icons.of(Material.PAPER, "<white>Preview",
                List.of("<gray>What your messages look like:", "", preview(style))));

        int column = 1;
        for (NamedTextColor color : WHO_COLORS) {
            if (column == 4) {
                column = 5;   // the preview sits at column 4 of this band
            }
            band(MenuLayout.WHO, column, colorIcon(color, style), click -> choose(style.withColor(color)));
            column++;
        }

        column = 1;
        for (NamedTextColor color : RULES_COLORS) {
            band(MenuLayout.RULES, column, colorIcon(color, style), click -> choose(style.withColor(color)));
            column++;
        }

        column = 1;
        for (NamedTextColor color : LAND_COLORS) {
            band(MenuLayout.LAND, column, colorIcon(color, style), click -> choose(style.withColor(color)));
            column++;
        }
        for (TextDecoration decoration : DECORATIONS) {
            band(MenuLayout.LAND, column, decorationIcon(decoration, style),
                    click -> choose(style.withDecoration(decoration, !style.has(decoration))));
            column++;
        }
        band(MenuLayout.LAND, column, Icons.of(Material.BARRIER, "<red>No colour",
                        "<gray>Back to how everybody else's", "<gray>messages look by default."),
                click -> choose(ChatStyle.DEFAULT));
    }

    private void choose(ChatStyle chosen) {
        services.styles().set(viewer.getUniqueId(), chosen);
        refresh();
    }

    /** One swatch — its own colour, named, with a check mark on whichever is chosen right now. */
    private ItemStack colorIcon(NamedTextColor color, ChatStyle current) {
        boolean chosen = color.equals(current.color());
        String name = "<" + NamedTextColor.NAMES.key(color) + ">" + ColorSwatches.readable(color);
        return Icons.of(ColorSwatches.materialFor(color), chosen ? name + " <green>✔" : name,
                chosen ? "<green>Your colour right now." : "<gray>Click to choose.");
    }

    /** One decoration toggle — lit when it is on, the same grammar {@code SettingsMenu} uses for a flag. */
    private ItemStack decorationIcon(TextDecoration decoration, ChatStyle current) {
        boolean on = current.has(decoration);
        String label = readable(decoration);
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "<green>" : "<gray>") + label,
                on ? "<green>On — click to turn off." : "<gray>Off — click to turn on.");
    }

    private String preview(ChatStyle style) {
        String plain = viewer.getName() + ": Hey, this looks pretty good!";
        StringBuilder tags = new StringBuilder();
        if (style.color() != null) {
            tags.append('<').append(NamedTextColor.NAMES.key(style.color())).append('>');
        }
        for (TextDecoration decoration : DECORATIONS) {
            if (style.has(decoration)) {
                tags.append('<').append(TextDecoration.NAMES.key(decoration)).append('>');
            }
        }
        return tags + plain;
    }

    private static String readable(TextDecoration decoration) {
        String key = TextDecoration.NAMES.key(decoration);
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
