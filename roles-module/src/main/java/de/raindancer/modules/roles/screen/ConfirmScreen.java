package de.raindancer.modules.roles.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;

import java.util.List;

/** Core's confirmation dialog, named here so this module's grammar test can see every choice confirms. */
final class ConfirmScreen extends ConfirmMenu implements IRolesScreen {

    ConfirmScreen(Player viewer, Brand brand, Menu parent, String question, List<String> consequences,
                  String closingLine, Runnable onYes) {
        super(viewer, brand, parent, question, consequences, closingLine, onYes);
    }

    @Override
    public String describe() {
        return "asking before a role is taken, since it holds for days";
    }
}
