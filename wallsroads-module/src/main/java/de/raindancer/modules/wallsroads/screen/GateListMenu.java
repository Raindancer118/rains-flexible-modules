package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Every opening a road has cut through this wall.
 *
 * <p>Two different decisions on one row, which is why they are two different clicks: <b>left</b>
 * works the gate (open or shut, the thing a gate does), <b>right</b> seals or unseals the opening
 * (bricking it up in the wall's own material, a decision about the wall).
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
        String roadName = services.registry().road(gate.roadId())
                .map(RoadPath::name).orElse("a road that is gone");
        String state = gate.sealed() ? "<red>Sealed" : gate.shut() ? "<gold>Shut" : "<green>Open";
        Material icon = gate.sealed() ? wall.material()
                : gate.shut() ? Material.IRON_BARS : Material.OAK_FENCE_GATE;

        return Icons.of(icon, state + " — " + gate.width() + " wide",
                "<gray>Where " + roadName + " crosses",
                "<gray>Passage height " + gate.height(),
                "",
                gate.sealed()
                        ? "<yellow>Right click <gray>to open the wall here again"
                        : "<yellow>Left click <gray>to " + (gate.shut() ? "open" : "shut") + " the gates",
                gate.sealed() ? "" : "<yellow>Right click <gray>to seal it up in " + wall.material().name()
                        .toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }

    @Override
    protected void onClick(Gate gate, InventoryClickEvent event) {
        if (event.isRightClick()) {
            if (gate.sealed()) {
                services.service().reopenGate(wall, gate.id(), this::refresh);
            } else {
                services.service().sealGate(wall, gate.id(), this::refresh);
            }
            return;
        }
        if (gate.sealed()) {
            return;
        }
        if (gate.shut()) {
            services.service().openGate(wall, gate.id(), this::refresh);
        } else {
            services.service().shutGate(wall, gate.id(), this::refresh);
        }
    }
}
