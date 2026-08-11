package de.raindancer.modules.chained.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.chained.ChainedServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>Everything about the page is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the
 * right, and the consequences on the middle button — the arrangement being identical everywhere is
 * the whole feature, see {@code MODULE-LAYOUT.md}. What is left here is taking
 * {@code ChainedServices} so this module's screens are constructed alike, and naming itself so this
 * module's own {@code ScreenGrammarTest} can go on proving that resetting the map is guarded by a
 * confirmation.
 */
public final class ConfirmScreen extends ConfirmMenu implements IChainedScreen {

    public ConfirmScreen(ChainedServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be undone";
    }
}
