package de.raindancer.modules.names.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.names.NamesServices;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.util.Naming;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The manual: every item that does something to a name tag, shown as itself.
 *
 * <h2>Why this screen exists</h2>
 * None of this module's recipes is a Bukkit recipe — {@code rules.CraftRule} explains why none of them
 * can be — so not one of them appears in the recipe book, and a feature nobody can discover is a feature
 * nobody has. The page is built from the palette that is actually loaded, so a server that has changed a
 * dye, added an item or deleted the obfuscated line gets a manual that matches its own rules rather than
 * the ones this module shipped with.
 *
 * <h2>Why a screen as well as a chat listing</h2>
 * Because the answer to "which blue is that?" is the colour, not its name. Here every entry is the item
 * you actually craft with, wearing the colour it actually gives, at the size everything else in the
 * window is — and the two blues that read as the same two words in chat are visibly different here. The
 * chat listing stays for the console, which has no inventory to open.
 */
public final class PaletteMenu extends PaginatedMenu<Map.Entry<Material, Reagent>>
        implements INamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What a shade is demonstrated on: it only changes a colour that is already there. */
    private static final NameStyle MID_GREY = NameStyle.NONE.withColour(NamedTextColor.GRAY);

    private final NamesServices services;

    public PaletteMenu(NamesServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Coloured names");
    }

    @Override
    public String breadcrumb() {
        return "Coloured names";
    }

    @Override
    protected List<Map.Entry<Material, Reagent>> entries() {
        return services.colours().ordered();
    }

    /**
     * An empty palette is a real state, not a bug: an owner who deleted every line meant it.
     *
     * <p>It says where to put them back rather than only that there are none, because the file that
     * needs editing is the one thing nobody can guess from inside the game.
     */
    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing dyes a name tag on this server",
                "<gray>Every colour, decoration and shade has been removed",
                "<gray>from the config, so there is nothing to craft.",
                "<dark_gray>They live under colours:, decorations: and shades:.");
    }

    /**
     * One reagent, shown in itself.
     *
     * <p>The icon is the item you craft with and the name is what that item does, painted in what it
     * does it in — which is the only description of a colour anybody actually reads.
     */
    @Override
    protected ItemStack icon(Map.Entry<Material, Reagent> entry) {
        Reagent reagent = entry.getValue();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + what(reagent));
        lore.add("");
        lore.add("<dark_gray>" + how(reagent));
        lore.add("<dark_gray>Click to be told again in chat.");

        // A config can name a material that is a block and not an item — WATER, or a potted cactus.
        // Nothing can ever put one in a crafting grid, so the entry is harmless everywhere else; here
        // it would be an ItemStack that cannot exist, and the whole page would fail to draw over one
        // line in a file. Shown as a name tag instead, with the name it was configured under, which is
        // the thing the owner has to go and correct. isItem() is asked here rather than when the file
        // is read because it needs a running server, and the palette is read without one.
        Material material = entry.getKey().isItem() ? entry.getKey() : Material.NAME_TAG;
        if (material != entry.getKey()) {
            lore.add("<red>" + Palette.pretty(entry.getKey()) + " is not an item,");
            lore.add("<red>so nothing can be crafted with it.");
        }

        return Icons.of(material, MINI.serialize(sample(reagent)), lore);
    }

    /**
     * Says the same thing in chat.
     *
     * <p>So it can be read after the window is closed, and pasted to somebody else. A button that did
     * nothing at all would be the one thing on the page a player tries twice.
     */
    @Override
    protected void onClick(Map.Entry<Material, Reagent> entry, InventoryClickEvent event) {
        Reagent reagent = entry.getValue();
        services.messages().send(viewer, keyFor(reagent),
                "item", Palette.pretty(entry.getKey()),
                "what", reagent.describe());
    }

    /**
     * The prose, drawn by Core as a book because this returns lines.
     *
     * <p>Generated from the settings that are loaded — the ceiling is the configured one and the
     * cauldron line only appears on a server that has it — so the manual cannot come to describe rules
     * this server does not have.
     */
    @Override
    protected List<String> helpLines() {
        List<String> lines = new ArrayList<>(lore("names.manual.intro",
                "stops", services.config().stops()));
        if (services.config().washInCauldron()) {
            lines.addAll(lore("names.manual.wash"));
        }
        if (services.config().colourMobNames()) {
            lines.addAll(lore("names.manual.mobs"));
        }
        return lines;
    }

    /** The reagent's own words, painted in what it does. */
    private static Component sample(Reagent reagent) {
        NameStyle base = reagent instanceof Reagent.Shade ? MID_GREY : NameStyle.NONE;
        return Naming.styled(reagent.describe(), reagent.appliedTo(base));
    }

    private static String what(Reagent reagent) {
        return switch (reagent) {
            case Reagent.Colour colour -> "Dyes a name tag " + colour.label() + ".";
            case Reagent.Decoration decoration -> "Turns " + decoration.describe()
                    + " on, and off again.";
            case Reagent.Shade shade -> "Makes every colour on the tag " + shade.label() + ".";
        };
    }

    private static String how(Reagent reagent) {
        return switch (reagent) {
            case Reagent.Colour ignored -> "Craft it with a name tag.";
            case Reagent.Decoration ignored -> "Craft it with a name tag. Again to undo it.";
            case Reagent.Shade ignored -> "Craft it with a tag that already has a colour.";
        };
    }

    private static String keyFor(Reagent reagent) {
        return switch (reagent) {
            case Reagent.Colour ignored -> "names.reagent.colour";
            case Reagent.Decoration ignored -> "names.reagent.decoration";
            case Reagent.Shade ignored -> "names.reagent.shade";
        };
    }

    /**
     * Wording out of {@code messages.yml}, as the MiniMessage that {@code Icons} takes.
     *
     * <p>Rendered by {@code Messages} and serialised back rather than kept as literals here, because a
     * second copy of the wording is how a manual comes to describe a rule the server no longer has.
     */
    private List<String> lore(String key, Object... values) {
        return services.messages().lines(key, values).stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "every dye, decoration and shade this server has, painted in itself";
    }
}
