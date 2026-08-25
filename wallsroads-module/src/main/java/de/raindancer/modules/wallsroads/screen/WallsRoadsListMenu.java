package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
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
import java.util.UUID;

/**
 * A list of walls, of roads, or of both — one class, because the three differ by what they leave out
 * and by nothing else. Reached from {@link WallsRoadsMenu}, which is the front page.
 *
 * <p>Walls before roads and then by name, because that is the order somebody thinks in — a list in
 * creation order means a player with a dozen structures has their walls scattered among their roads.
 *
 * <p>Each row says where it is and how big, so "which of my three roads is the one over the ocean" is
 * answerable without opening all three.
 */
public final class WallsRoadsListMenu extends PaginatedMenu<WallsRoadsListMenu.Entry> {

    /** Which kinds this list shows. */
    public enum Filter {
        WALLS, ROADS, ALL
    }

    /** A wall or a road, wrapped as one type for one paginated list — the id says which. */
    public record Entry(String id, String name, boolean isWall, boolean built, String world,
                        int size, String detail, UUID owner) {
    }

    private final WallsRoadsServices services;
    private final Filter filter;

    /** Whose to show, or {@code null} for everybody's — which is the staff browser. */
    private final UUID owner;

    public WallsRoadsListMenu(WallsRoadsServices services, Player viewer, Menu parent,
                              Filter filter, UUID owner) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.filter = filter;
        this.owner = owner;
    }

    @Override
    protected Component title() {
        String whose = owner == null ? "Every" : "Your";
        return Component.text(switch (filter) {
            case WALLS -> whose + " walls";
            case ROADS -> whose + " roads";
            case ALL -> owner == null ? "Everything built here" : "Your walls and roads";
        });
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> all = new ArrayList<>();
        if (filter != Filter.ROADS) {
            List<Wall> walls = owner == null
                    ? services.registry().allWalls() : services.registry().wallsOwnedBy(owner);
            for (Wall wall : walls) {
                all.add(new Entry(wall.id(), wall.name(), true, wall.isBuilt(), wall.world(),
                        wall.outline().vertices().size(),
                        wall.height() + " tall, " + wall.thickness() + " thick", wall.owner()));
            }
        }
        if (filter != Filter.WALLS) {
            List<RoadPath> roads = owner == null
                    ? services.registry().allRoads() : services.registry().roadsOwnedBy(owner);
            for (RoadPath road : roads) {
                all.add(new Entry(road.id(), road.name(), false, road.isBuilt(), road.world(),
                        (int) Math.round(road.path().length()),
                        (int) road.width() + " wide", road.owner()));
            }
        }
        all.sort(Comparator.comparing((Entry entry) -> !entry.isWall())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    /**
     * Says there is nothing here, and where the way out is.
     *
     * <p>Not a start button of its own: the two sticks live on the front page this was opened from,
     * and a second copy here would be one job done by two things — with the copy a new player's eye
     * lands on being the one that is not where it will be next time.
     */
    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing here yet",
                "<gray>Go back and take a stick to mark one out.");
    }

    @Override
    protected ItemStack icon(Entry entry) {
        Material material = entry.isWall()
                ? (entry.built() ? Material.STONE_BRICKS : Material.COBBLESTONE)
                : (entry.built() ? Material.GRAVEL : Material.DIRT_PATH);

        List<String> lore = new ArrayList<>();
        lore.add(entry.built() ? "<green>Standing" : "<gray>Not built");
        if (owner == null) {
            // Only in the staff browser: on somebody's own list every row would say the same name.
            lore.add("<gray>built by <white>" + nameOf(entry.owner()));
        }
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

    private String nameOf(UUID who) {
        if (who == null) {
            return "somebody who has gone";
        }
        String known = services.server().getOfflinePlayer(who).getName();
        return known == null ? who.toString().substring(0, 8) : known;
    }
}
