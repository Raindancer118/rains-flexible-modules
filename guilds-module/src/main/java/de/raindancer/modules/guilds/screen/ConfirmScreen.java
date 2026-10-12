package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.guilds.GuildsServices;
import org.bukkit.entity.Player;

import java.util.List;

/** "Are you sure?" — Core's dialog under this module's name, which is what ScreenGrammarTest looks for. */
public final class ConfirmScreen extends ConfirmMenu implements IGuildsScreen {

    public ConfirmScreen(GuildsServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be undone";
    }
}
