package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.selection.WallsRoadsSelectionFlow;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The front page: what {@code /wallsroads} opens.
 *
 * <h2>Why a page rather than the list</h2>
 * The list was the front door until now, and it answers one question — "what do I already have?" —
 * while every other thing this module does had nowhere to live. Starting a road, reading the manual,
 * sending gates to your map and changing how the server routes are not "things in a list", and
 * hanging them off the corners of one is how a screen ends up as a grid of buttons in no order.
 *
 * <p>So the page has rows that mean something:
 *
 * <pre>
 *   who   ·  your roads     your walls      mark a road     mark a wall
 *   rules ·  everything     how it builds   onto your map
 *          [ the manual ]
 * </pre>
 *
 * <p>Which means somebody looking for "where do I start one" reads one row of four rather than
 * hunting the corners of a list, and the next thing this module grows has an obvious home.
 *
 * <h2>Buttons that are not yours are greyed, never hidden</h2>
 * The staff pair stays on screen for everybody, saying whose it is. Hiding makes the page a different
 * shape per viewer, so nobody can be told "the second one along", and "why can I not see it" has no
 * answer on screen.
 */
public final class WallsRoadsMenu extends Menu {

    private final WallsRoadsServices services;

    public WallsRoadsMenu(WallsRoadsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent, 4);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Walls and Roads");
    }

    @Override
    public String breadcrumb() {
        return "Walls and Roads";
    }

    @Override
    protected void render() {
        int myWalls = services.registry().wallsOwnedBy(viewer.getUniqueId()).size();
        int myRoads = services.registry().roadsOwnedBy(viewer.getUniqueId()).size();
        boolean staff = viewer.hasPermission(PermissionNodes.MANAGE_ANY);
        boolean mayMark = services.config().openCreation()
                || viewer.hasPermission(PermissionNodes.CREATE);

        band(MenuLayout.WHO, 1, Icons.of(myRoads > 0 ? Material.GRAVEL : Material.DIRT_PATH,
                        "<white>Your roads",
                        "<gray>" + count(myRoads, "road"),
                        "",
                        "<yellow>Click <gray>to open the list"),
                click -> new WallsRoadsListMenu(services, viewer, this,
                        WallsRoadsListMenu.Filter.ROADS, viewer.getUniqueId()).open());

        band(MenuLayout.WHO, 3, Icons.of(myWalls > 0 ? Material.STONE_BRICKS : Material.COBBLESTONE,
                        "<white>Your walls",
                        "<gray>" + count(myWalls, "wall"),
                        "",
                        "<yellow>Click <gray>to open the list"),
                click -> new WallsRoadsListMenu(services, viewer, this,
                        WallsRoadsListMenu.Filter.WALLS, viewer.getUniqueId()).open());

        band(MenuLayout.WHO, 5, mayMark,
                Icons.of(Material.STICK, "<white>Mark out a road",
                        "<gray>Click corners along the way it should run.",
                        "<gray>It bridges, tunnels and curves on its own.",
                        "",
                        "<yellow>Click to start <dark_gray>· puts the stick in your hand"),
                "Marking is limited to builders on this server",
                click -> {
                    viewer.closeInventory();
                    services.selectionFlow().begin(viewer, WallsRoadsSelectionFlow.Purpose.ROAD);
                });

        band(MenuLayout.WHO, 7, mayMark,
                Icons.of(Material.STONE_BRICK_WALL, "<white>Mark out a wall",
                        "<gray>Click the corners of the shape it should enclose.",
                        "<gray>Roads crossing it cut their own gates.",
                        "",
                        "<yellow>Click to start <dark_gray>· puts the stick in your hand"),
                "Marking is limited to builders on this server",
                click -> {
                    viewer.closeInventory();
                    services.selectionFlow().begin(viewer, WallsRoadsSelectionFlow.Purpose.WALL);
                });

        band(MenuLayout.RULES, 2, staff,
                Icons.of(Material.BOOKSHELF, "<white>Everything on this server",
                        "<gray>" + count(services.registry().wallCount(), "wall")
                                + ", " + count(services.registry().roadCount(), "road"),
                        "<gray>Whoever built them.",
                        "",
                        "<yellow>Click <gray>to browse"),
                "Only staff can look at everybody's",
                click -> new WallsRoadsListMenu(services, viewer, this,
                        WallsRoadsListMenu.Filter.ALL, null).open());

        band(MenuLayout.RULES, 4, staff,
                Icons.of(Material.COMPASS, "<white>How roads are built here",
                        "<gray>The routing thresholds, and what this server allows.",
                        "<dark_gray>also /wallsroads config",
                        "",
                        "<yellow>Click <gray>to open"),
                "Only staff can change how the server builds",
                click -> new WallsRoadsConfigMenu(services, viewer, this).open());

        boolean mapAvailable = services.mapLink().available();
        band(MenuLayout.RULES, 6, mapAvailable,
                Icons.of(Material.FILLED_MAP, "<white>Onto your own map",
                        "<gray>Sends the gates and road ends around you",
                        "<gray>to your client's map as waypoints.",
                        "<dark_gray>also /wallsroads map",
                        "",
                        "<yellow>Click <gray>to send them"),
                "This server has no client-map support running",
                click -> {
                    viewer.closeInventory();
                    viewer.performCommand("wallsroads map");
                });

        danger(Icons.of(Material.WRITTEN_BOOK, "<white>The manual",
                        "<gray>How walls and roads work, as a book you keep.",
                        "<dark_gray>also /wallsroads manual"),
                click -> {
                    viewer.closeInventory();
                    services.screens().manual(viewer);
                });
    }

    /** "no roads" reads better than "0 roads", and is what somebody with none needs to be told. */
    private static String count(int many, String noun) {
        if (many == 0) {
            return "no " + noun + "s yet";
        }
        return many + " " + noun + (many == 1 ? "" : "s");
    }
}
