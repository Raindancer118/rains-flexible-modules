package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import org.bukkit.entity.Player;

import java.util.List;

/** "Are you sure?" — Core's dialog, under this module's name. See claims-module's own copy of this. */
public final class ConfirmScreen extends ConfirmMenu {

    public ConfirmScreen(WallsRoadsServices services, Player viewer, Menu parent,
                         String question, List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }
}
