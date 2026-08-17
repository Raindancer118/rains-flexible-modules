package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.Wall;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Every gate a road has ever cut through this wall — sealed and open both shown, each with the one
 * button that does the opposite of whatever it currently is.
 */
public final class GateListMenu extends PaginatedMenu<Gate> {

    private final WallsRoadsServices services;
    private final Wall wall;

    public GateListMenu(WallsRoadsServices services, Player viewer, Wall wall, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.wall = wall;
    }

    @Override
    protected Component title() {
        return Component.text(wall.name() + " — Gates");
    }

    @Override
    protected List<Gate> entries() {
        return wall.gates();
    }

    @Override
    protected ItemStack icon(Gate gate) {
        boolean sealed = gate.sealed();
        return Icons.of(sealed ? Material.OAK_FENCE_GATE : Material.AIR,
                (sealed ? "<red>Sealed" : "<green>Open") + " — " + gate.openingColumns().size() + " wide",
                "<gray>Cut by road id " + gate.roadId(),
                "<gray>Passage height " + gate.height(),
                "",
                "<yellow>Click <gray>to " + (sealed ? "reopen it" : "seal it"));
    }

    @Override
    protected void onClick(Gate gate, InventoryClickEvent event) {
        if (gate.sealed()) {
            services.service().reopenGate(wall, gate.id(), this::refresh);
        } else {
            services.service().sealGate(wall, gate.id(), this::refresh);
        }
    }
}
