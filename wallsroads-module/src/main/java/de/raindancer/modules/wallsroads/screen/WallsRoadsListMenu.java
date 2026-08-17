package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Every wall and road this player owns. */
public final class WallsRoadsListMenu extends PaginatedMenu<WallsRoadsListMenu.Entry> {

    /** A wall or a road, wrapped as one type for one paginated list — the id says which. */
    public record Entry(String id, String name, boolean isWall, boolean built) {
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
            all.add(new Entry(wall.id(), wall.name(), true, wall.isBuilt()));
        }
        for (RoadPath road : services.registry().roadsOwnedBy(viewer.getUniqueId())) {
            all.add(new Entry(road.id(), road.name(), false, road.isBuilt()));
        }
        return all;
    }

    @Override
    protected ItemStack icon(Entry entry) {
        Material material = entry.isWall()
                ? (entry.built() ? Material.STONE_BRICKS : Material.COBBLESTONE)
                : (entry.built() ? Material.GRAVEL : Material.DIRT_PATH);
        return Icons.of(material, (entry.isWall() ? "<white>Wall — " : "<white>Road — ") + entry.name(),
                entry.built() ? "<green>Standing" : "<gray>Not built",
                "",
                "<yellow>Click <gray>to open");
    }

    @Override
    protected void onClick(Entry entry, InventoryClickEvent event) {
        if (entry.isWall()) {
            services.registry().wall(entry.id()).ifPresent(wall ->
                    new WallEditMenu(services, viewer, wall, this).open());
        } else {
            services.registry().road(entry.id()).ifPresent(road ->
                    new RoadEditMenu(services, viewer, road, this).open());
        }
    }
}
