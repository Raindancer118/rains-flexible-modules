package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * {@code /cosmetics}: your name as everybody sees it, and the ways to change it — a preset, your own
 * colours, the decorations. Every click takes effect at once; the preview is the real thing.
 */
public final class NameStyleMenu extends Menu implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public NameStyleMenu(CosmeticsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your name");
    }

    @Override
    public String breadcrumb() {
        return "Your name";
    }

    @Override
    protected void render() {
        NameStyleService names = services.names();
        NameStyle style = names.current(viewer.getUniqueId());
        Grants grants = names.grantsOf(viewer);

        band(MenuLayout.WHO, 2, Icons.of(Material.NAME_TAG, "<white>Presets",
                        "<gray>Ready-made colours and gradients.", "", "<dark_gray>Click to pick one."),
                click -> new PresetMenu(services, viewer, this).open());

        band(MenuLayout.WHO, 4, preview(style));

        band(MenuLayout.WHO, 6, grants.colour() || grants.gradient(),
                Icons.of(Material.BRUSH, "<white>Your own colours",
                        "<gray>One colour, or up to " + names.maxStops() + " in a gradient.",
                        "", "<dark_gray>Click to mix them."),
                "Needs " + PermissionNodes.NAME_COLOUR,
                click -> new GradientMenu(services, viewer, this).open());

        int column = 1;
        for (TextDecoration decoration : TextDecoration.values()) {
            boolean allowed = grants.decorations().contains(decoration);
            band(MenuLayout.RULES, column, allowed, decorationIcon(decoration, style),
                    "Needs " + PermissionNodes.decoration(decoration),
                    click -> {
                        names.wear(viewer, style.toggle(decoration), false);
                        refresh();
                    });
            column += column == 3 ? 2 : 1;
        }

        band(MenuLayout.LAND, 4, Icons.of(Material.NAME_TAG, "<white>Preview your nametag",
                        "<gray>Closes this and floats the name over",
                        "<gray>your head in front of you for a moment.", "",
                        "<dark_gray>Click to see it."),
                click -> {
                    viewer.closeInventory();
                    names.previewNametag(viewer);
                });

        if (!style.isEmpty()) {
            danger(Icons.of(Material.BARRIER, "<red>Back to a plain name",
                            "<gray>Takes off " + names.describe(style) + ".", "",
                            "<dark_gray>Asks first."),
                    click -> new ConfirmScreen(services, viewer, this, "Back to a plain name?",
                            List.of("<gray>Your colours and decorations are taken off.",
                                    "<gray>A custom gradient has to be mixed again."),
                            () -> names.wear(viewer, NameStyle.NONE)).open());
        }
    }

    private ItemStack preview(NameStyle style) {
        String painted = MINI.serialize(NameStyleService.painted(viewer.getName(), style));
        return Icons.head(viewer.getUniqueId(), painted,
                "<gray>How everybody sees you — in chat,",
                "<gray>in the player list and on your nickname.",
                "",
                "<dark_gray>" + services.names().describe(style));
    }

    private static ItemStack decorationIcon(TextDecoration decoration, NameStyle style) {
        boolean on = style.has(decoration);
        String word = decoration.name().toLowerCase(Locale.ROOT);
        String label = Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "<green>" : "<gray>") + "<" + word + ">" + label,
                on ? "<green>On — click to turn off." : "<gray>Off — click to turn on.");
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("cosmetics.help").stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "your name, and everything that paints it";
    }
}
