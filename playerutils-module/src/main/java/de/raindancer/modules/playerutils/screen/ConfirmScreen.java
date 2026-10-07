package de.raindancer.modules.playerutils.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import org.bukkit.entity.Player;

import java.util.List;

/** Core's "are you sure?", under this module's name so {@code ScreenGrammarTest} can see it guard. */
public final class ConfirmScreen extends ConfirmMenu implements IPlayerUtilsScreen {

    public ConfirmScreen(PlayerUtilsServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something is taken from somebody for good";
    }
}
