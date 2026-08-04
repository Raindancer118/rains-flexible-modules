package de.raindancer.modules.farmworld.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.farmworld.FarmWorldServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>Everything about the page is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the right, and
 * the consequences on the middle button. That is deliberate and it is checked in Core, because the
 * arrangement being the same everywhere is the whole feature — a dialog that swaps the two answers is one
 * people learn to click through and then get wrong exactly once, on the page that deletes something.
 *
 * <p>Which page that is, here, is the one that regenerates a farm world: three worlds regenerated, and
 * everything anybody had built in them with it. There is no undo behind that button and there is no backup —
 * so of every {@code ConfirmMenu} in this repository, this is the one whose two answers being in the
 * habitual places matters most.
 *
 * <p>Written out rather than used directly at the call site so that the module's own
 * {@code ScreenGrammarTest} can go on proving that every {@code danger(} button on every page of this module
 * is guarded by a confirmation.
 */
public final class ConfirmScreen extends ConfirmMenu implements IFarmWorldScreen {

    public ConfirmScreen(FarmWorldServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be undone";
    }
}
