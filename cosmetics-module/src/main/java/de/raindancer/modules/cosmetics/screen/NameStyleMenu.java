package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.choose.StyleEditor;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * {@code /cosmetics}: your name as everybody sees it, and the ways to change it — a preset, or your own
 * colours and decorations through Core's style editor. Every click takes effect at once; the preview is the real thing.
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

        band(MenuLayout.WHO, 2, Icons.of(Material.NAME_TAG, "<white>Presets",
                        "<gray>Ready-made colours and gradients.", "", "<dark_gray>Click to pick one."),
                click -> new PresetMenu(services, viewer, this).open());

        band(MenuLayout.WHO, 4, preview(style));

        // Always open: whoever may not colour may still have a decoration, and the editor greys each
        // button with the node it needs.
        band(MenuLayout.WHO, 6, Icons.of(Material.BRUSH, "<white>Your own colours",
                        "<gray>One colour, or up to " + names.maxStops() + " in a gradient,",
                        "<gray>and bold, italic and the rest.", "", "<dark_gray>Click to mix them."),
                click -> StyleEditor.of(viewer, services.brand(), this)
                        .heading("Your colours")
                        .sample(viewer.getName())
                        .current(() -> names.current(viewer.getUniqueId()))
                        .onChange(changed -> names.wear(viewer, changed, false))
                        .grants(names.styleGrantsOf(viewer))
                        .palette(services.offered().swatches())
                        .maxStops(names.maxStops())
                        .open());

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

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("cosmetics.help").stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "your name, and everything that paints it";
    }
}
