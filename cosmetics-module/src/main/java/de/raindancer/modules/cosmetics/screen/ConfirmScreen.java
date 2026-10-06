package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import org.bukkit.entity.Player;

import java.util.List;

/** Core's "are you sure?", under this module's name so {@code ScreenGrammarTest} can see it guard. */
public final class ConfirmScreen extends ConfirmMenu implements ICosmeticsScreen {

    public ConfirmScreen(CosmeticsServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before a name style is thrown away";
    }
}
