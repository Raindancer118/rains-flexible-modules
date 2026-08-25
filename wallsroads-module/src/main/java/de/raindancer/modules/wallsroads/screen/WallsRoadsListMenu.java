package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.selection.WallsRoadsSelectionFlow;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The front door: every wall and road this player owns, and the two ways to start a new one.
 *
 * <p>Walls before roads and then by name, because that is the order somebody thinks in — a list in
 * creation order means a player with a dozen structures has their walls scattered among their roads.
 *
 * <p>Each row says where it is and how big, so "which of my three roads is the one over the ocean" is
 * answerable without opening all three.
 */
public final class WallsRoadsListMenu extends PaginatedMenu<WallsRoadsListMenu.Entry> {

    /** A wall or a road, wrapped as one type for one paginated list — the id says which. */
    public record Entry(String id, String name, boolean isWall, boolean built, String world,
                        int size, String detail) {
    }

    private final WallsRoadsServices services;

    public WallsRoadsListMenu(WallsRoadsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Your walls and roads");
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> all = new ArrayList<>();
        for (Wall wall : services.registry().wallsOwnedBy(viewer.getUniqueId())) {
            all.add(new Entry(wall.id(), wall.name(), true, wall.isBuilt(), wall.world(),
                    wall.outline().vertices().size(),
                    wall.height() + " tall, " + wall.thickness() + " thick"));
        }
        for (RoadPath road : services.registry().roadsOwnedBy(viewer.getUniqueId())) {
            all.add(new Entry(road.id(), road.name(), false, road.isBuilt(), road.world(),
                    (int) Math.round(road.path().length()),
                    (int) road.width() + " wide"));
        }
        all.sort(Comparator.comparing((Entry entry) -> !entry.isWall())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    @Override
    protected void decorate() {
        super.decorate();

        // Bottom centre, the way every other module puts its manual there.
        danger(Icons.of(Material.WRITTEN_BOOK, "<white>The manual",
                        "<gray>How walls and roads work, as a book you keep.",
                        "<dark_gray>also /wallsroads manual"),
                click -> {
                    viewer.closeInventory();
                    services.screens().manual(viewer);
                });

        boolean mayMark = services.config().openCreation()
                || viewer.hasPermission(PermissionNodes.CREATE);

        // Always on screen, not only while the list is empty: the old placement made this the one
        // thing on the page that stopped being reachable the moment somebody built a first road.
        toolbar(2, mayMark, Icons.of(Material.DIRT_PATH, "<white>Mark out a new road",
                        "<gray>Click corners along the way it should run.",
                        "<gray>It bridges, tunnels and curves on its own.",
                        "",
                        "<yellow>Click to start <dark_gray>· puts the stick in your hand"),
                "Marking is limited to builders on this server",
                click -> {
                    viewer.closeInventory();
                    services.selectionFlow().begin(viewer, WallsRoadsSelectionFlow.Purpose.ROAD);
                });

        toolbar(6, mayMark, Icons.of(Material.STONE_BRICK_WALL, "<white>Mark out a new wall",
                        "<gray>Click the corners of the shape it should enclose.",
                        "<gray>Roads crossing it cut their own gates.",
                        "",
                        "<yellow>Click to start <dark_gray>· puts the stick in your hand"),
                "Marking is limited to builders on this server",
                click -> {
                    viewer.closeInventory();
                    services.selectionFlow().begin(viewer, WallsRoadsSelectionFlow.Purpose.WALL);
                });

        if (viewer.hasPermission(PermissionNodes.MANAGE_ANY)) {
            toolbar(4, Icons.of(Material.COMPASS, "<white>How roads are built here",
                            "<gray>The routing thresholds, and what this server allows.",
                            "<dark_gray>also /wallsroads config"),
                    click -> new WallsRoadsConfigMenu(services, viewer, this).open());
        }
    }

    /**
     * Says there is nothing here rather than repeating the way out.
     *
     * <p>The two start buttons are on the toolbar now, always — a third copy in the empty slot would
     * be one job done by two things, and the one a new player's eye lands on would be the one that is
     * not where every other page keeps it.
     */
    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>You have no walls or roads yet",
                "<gray>Take a stick from below to mark one out.");
    }

    @Override
    protected ItemStack icon(Entry entry) {
        Material material = entry.isWall()
                ? (entry.built() ? Material.STONE_BRICKS : Material.COBBLESTONE)
                : (entry.built() ? Material.GRAVEL : Material.DIRT_PATH);

        List<String> lore = new ArrayList<>();
        lore.add(entry.built() ? "<green>Standing" : "<gray>Not built");
        lore.add("<gray>" + (entry.isWall()
                ? entry.size() + " corners, " + entry.detail()
                : entry.size() + " blocks long, " + entry.detail()));
        lore.add("<gray>in <white>" + entry.world());
        lore.add("");
        lore.add("<yellow>Click <gray>to open");
        lore.add("<yellow>Right click <gray>to be shown its outline");

        return Icons.of(material,
                (entry.isWall() ? "<white>Wall — " : "<white>Road — ") + entry.name(), lore);
    }

    @Override
    protected void onClick(Entry entry, InventoryClickEvent event) {
        if (event.isRightClick()) {
            showOutline(entry);
            return;
        }
        if (entry.isWall()) {
            services.registry().wall(entry.id()).ifPresent(wall ->
                    new WallEditMenu(services, viewer, wall, this).open());
        } else {
            services.registry().road(entry.id()).ifPresent(road ->
                    new RoadEditMenu(services, viewer, road, this).open());
        }
    }

    /** Draws it in the air, if the viewer is in the world it is in — saying so beats an invisible outline. */
    private void showOutline(Entry entry) {
        viewer.closeInventory();
        if (!viewer.getWorld().getName().equals(entry.world())) {
            services.messages().send(viewer, "wallsroads.outline-elsewhere",
                    "name", entry.name(), "world", entry.world());
            return;
        }
        List<de.raindancer.core.world.geometry.ColumnPolygon.Column> corners = entry.isWall()
                ? services.registry().wall(entry.id())
                        .map(wall -> wall.effectiveOutline().vertices()).orElse(List.of())
                : services.registry().road(entry.id())
                        .map(road -> road.path().points()).orElse(List.of());
        if (corners.size() < 2) {
            return;
        }
        services.outline().draw(viewer, viewer.getWorld(), corners, viewer.getLocation().getBlockY(),
                new Particle.DustOptions(entry.isWall()
                        ? org.bukkit.Color.SILVER : org.bukkit.Color.ORANGE, 1.0f));
        services.messages().send(viewer, "wallsroads.outline-shown", "name", entry.name());
    }

    private static String pretty(String material) {
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
