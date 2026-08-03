package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.Selection;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The marking tool's own screen: what is being drawn, how far it has got, and how to finish.
 *
 * <p>Reached by right-clicking the air with the tool, which is the one gesture a player will find by accident.
 * It exists because the old flow had no way to see what you had marked — corners were shown in the world and
 * nowhere else, so somebody who lost track had to cancel and start again.
 */
public final class SelectionMenu extends ClaimScreen {

    public SelectionMenu(ClaimServices services, Player viewer, Menu parent) {
        super(services, viewer, null, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Marking out");
    }

    @Override
    protected void render() {
        Optional<Selection> maybe = services().selections().selection(viewer);
        if (maybe.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<gray>Nothing being marked",
                    "<gray>Start with <white>/claim new</white>."));
            return;
        }
        Selection selection = maybe.get();
        int needed = selection.mode() == Selection.Mode.RECTANGLE ? 2 : 3;

        List<String> progress = new ArrayList<>();
        progress.add("<gray>" + selection.pointCount() + " of " + needed + " corner(s) marked");
        progress.add("<gray>" + purposeOf(selection));
        if (selection.isComplete()) {
            progress.add("");
            progress.add("<green>ready to finish");
        }
        band(MenuLayout.WHO, 2, Icons.of(Material.MAP, "<white>So far", progress));

        band(MenuLayout.WHO, 4, selection.mode() == Selection.Mode.RECTANGLE
                        ? Icons.of(Material.PAPER, "<white>Shape: rectangle",
                                "<gray>Two opposite corners.",
                                "<dark_gray>click for a free outline instead")
                        : Icons.of(Material.LEAD, "<white>Shape: outline",
                                "<gray>As many corners as you like.",
                                "<dark_gray>click for a plain rectangle instead"),
                click -> {
                    // Switching shape keeps the corners: the first two of an outline are a rectangle's two, so
                    // somebody who realises halfway through does not start over.
                    selection.mode(selection.mode() == Selection.Mode.RECTANGLE
                            ? Selection.Mode.POLYGON : Selection.Mode.RECTANGLE);
                    refresh();
                });

        band(MenuLayout.WHO, 6, selection.isComplete(),
                Icons.of(Material.LIME_CONCRETE, "<green>Finish",
                        "<gray>Make the claim.",
                        "<dark_gray>you will be asked for a name"),
                "Mark " + (needed - selection.pointCount()) + " more corner(s) first",
                click -> {
                    viewer.closeInventory();
                    services().selectionFlow().finish(viewer);
                });

        toolbar(3, Icons.of(Material.STRUCTURE_VOID, "<gray>Undo the last corner",
                        "<gray>Or left click a block with the tool."),
                click -> {
                    if (!selection.removeLastPoint()) {
                        tell("selection.nothing-to-undo");
                    }
                    refresh();
                });

        toolbar(5, Icons.of(Material.RED_CONCRETE, "<red>Give up",
                        "<gray>Forget it and take the tool back."),
                click -> {
                    viewer.closeInventory();
                    services().selectionFlow().cancel(viewer);
                });
    }

    private static String purposeOf(Selection selection) {
        return switch (selection.purpose()) {
            case NEW_CLAIM -> "a new claim";
            case RESIZE_CLAIM -> "redrawing an existing claim";
            case NO_CLAIM_ZONE -> "an area nobody may claim";
        };
    }
}
