package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;

import java.util.List;

/** Core's confirmation dialog, named here so this module's grammar test can see every danger confirms. */
final class ConfirmScreen extends ConfirmMenu implements IEconomyScreen {

    ConfirmScreen(Player viewer, Brand brand, Menu parent, String question, List<String> consequences,
                  Runnable onYes) {
        super(viewer, brand, parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be taken back";
    }
}
