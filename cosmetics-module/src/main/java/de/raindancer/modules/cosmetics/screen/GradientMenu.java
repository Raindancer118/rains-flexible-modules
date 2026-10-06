package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Mixing your own colours: the stops in a row, left to right as they run across the name; add one
 * from the palette or as a typed hex code, click one to take it out, reverse, clear.
 *
 * <p>Every change is worn at once, so a stop that is not allowed is refused on the click that adds it —
 * with the reason — rather than at some "save" later.
 */
public final class GradientMenu extends Menu implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public GradientMenu(CosmeticsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your colours");
    }

    @Override
    public String breadcrumb() {
        return "Colours";
    }

    @Override
    protected void render() {
        NameStyleService names = services.names();
        NameStyle style = names.current(viewer.getUniqueId());
        Grants grants = names.grantsOf(viewer);
        int stops = style.colours().size();
        boolean room = stops < names.maxStops();
        // The first stop is a colour, every one after it makes a gradient.
        boolean mayAdd = room && (stops == 0 ? grants.colour() || grants.gradient() : grants.gradient());
        String addRefusal = !room ? "At most " + names.maxStops() + " colours"
                : "Needs " + (stops == 0 ? PermissionNodes.NAME_COLOUR : PermissionNodes.NAME_GRADIENT);

        band(MenuLayout.WHO, 2, mayAdd,
                Icons.of(Material.LIME_DYE, "<green>Add a colour",
                        "<gray>From the palette, onto the end.", "", "<dark_gray>Click to pick."),
                addRefusal,
                click -> new ColourPickMenu(services, viewer, this,
                        colour -> names.wear(viewer, style.withStop(colour), false)).open());

        band(MenuLayout.WHO, 3, mayAdd && grants.anyColour(),
                Icons.of(Material.NAME_TAG, "<green>Type a colour",
                        "<gray>Any colour as a hex code, like #8e2de2.", "", "<dark_gray>Click to type it."),
                mayAdd ? "Needs " + PermissionNodes.NAME_ANY_COLOUR : addRefusal,
                click -> AnvilInput.open(viewer, "A colour, like #8e2de2", "#", GradientMenu::hex,
                        colour -> {
                            names.wear(viewer, style.withStop(colour), false);
                            open();
                        },
                        this::open));

        band(MenuLayout.WHO, 4, Icons.head(viewer.getUniqueId(),
                MINI.serialize(NameStyleService.painted(viewer.getName(), style)),
                "<gray>" + names.describe(style)));

        band(MenuLayout.WHO, 5, style.isGradient(),
                Icons.of(Material.COMPARATOR, "<white>Reverse", "<gray>Runs the gradient the other way."),
                "Needs two colours or more",
                click -> {
                    names.wear(viewer, style.reversed(), false);
                    refresh();
                });

        band(MenuLayout.WHO, 6, stops > 0,
                Icons.of(Material.WATER_BUCKET, "<white>Clear the colours",
                        "<gray>Keeps bold, italic and the rest."),
                "No colours to clear",
                click -> {
                    names.wear(viewer, style.withoutColours(), false);
                    refresh();
                });

        boolean flowing = style.isAnimated();
        band(MenuLayout.WHO, 7, style.isGradient() && grants.animated(),
                Icons.of(flowing ? Material.LIME_DYE : Material.GRAY_DYE,
                        flowing ? "<green>Flowing" : "<white>Flowing",
                        "<gray>Lets the gradient drift along your", "<gray>name, round and round.", "",
                        flowing ? "<green>On — click to hold it still." : "<gray>Off — click to set it moving."),
                style.isGradient() ? "Needs " + PermissionNodes.NAME_ANIMATED : "Needs two colours or more",
                click -> {
                    names.wear(viewer, style.animated(!flowing), false);
                    refresh();
                });

        for (int index = 0; index < stops; index++) {
            int stop = index;
            cell(MenuLayout.LAND, index, stopIcon(style.colours().get(index), index),
                    click -> {
                        names.wear(viewer, style.withoutStop(stop), false);
                        refresh();
                    });
        }
    }

    private ItemStack stopIcon(TextColor colour, int index) {
        Material icon = services.offered().swatchOf(colour).map(PaletteColour::icon).orElse(Material.PAPER);
        String name = (index + 1) + ". " + services.offered().nameOf(colour);
        return Icons.of(icon,
                MINI.serialize(NameStyleService.painted(name, NameStyle.NONE.withColour(colour))),
                "<dark_gray>" + colour.asHexString(), "", "<gray>Click to take it out.");
    }

    private static Parsed<TextColor> hex(String typed) {
        TextColor colour = NameStyle.colourOf(typed);
        return colour == null
                ? Parsed.no("A # and six digits or a-f, like #8e2de2.")
                : Parsed.ok(colour);
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("cosmetics.help-gradient", "stops", services.names().maxStops())
                .stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "mixing your own name colours";
    }
}
