package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** The palette as swatches, each named in its own colour. Picking one hands it back and goes back. */
public final class ColourPickMenu extends PaginatedMenu<PaletteColour> implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;
    private final Consumer<TextColor> chosen;

    public ColourPickMenu(CosmeticsServices services, Player viewer, Menu parent, Consumer<TextColor> chosen) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.chosen = chosen;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Pick a colour");
    }

    @Override
    public String breadcrumb() {
        return "Colour";
    }

    @Override
    protected List<PaletteColour> entries() {
        return services.offered().palette();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>The palette is empty",
                "<gray>An owner adds colours under palette:",
                "<gray>in the cosmetics config.yml.");
    }

    @Override
    protected ItemStack icon(PaletteColour swatch) {
        String label = Character.toUpperCase(swatch.label().charAt(0)) + swatch.label().substring(1);
        return Icons.of(swatch.icon(),
                MINI.serialize(NameStyleService.painted(label, NameStyle.NONE.withColour(swatch.colour()))),
                "<dark_gray>" + swatch.colour().asHexString(),
                "",
                "<gray>Click to add it to your name.");
    }

    @Override
    protected void onClick(PaletteColour swatch, InventoryClickEvent event) {
        chosen.accept(swatch.colour());
        backToWhoeverOpenedThis();
    }

    @Override
    public String describe() {
        return "picking one colour from the palette";
    }
}
