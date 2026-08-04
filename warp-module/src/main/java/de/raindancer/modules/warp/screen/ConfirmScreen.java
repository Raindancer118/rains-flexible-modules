package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.warp.WarpServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>Everything about the page is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the
 * right, and the consequences on the middle button. That is deliberate and it is checked in Core,
 * because the arrangement being the same everywhere is the whole feature — a dialog that swaps the
 * two answers is one people learn to click through and then get wrong exactly once, on the page that
 * deletes something.
 *
 * <p>What is left here is two lines: taking {@code WarpServices} so the module's screens are
 * constructed alike, and naming itself so it can be found. Written out rather than used directly at
 * the call site so that the module's own {@code ScreenGrammarTest} can go on proving that every
 * {@code danger(} button on every page of this module is guarded by a confirmation.
 */
public final class ConfirmScreen extends ConfirmMenu implements IWarpScreen {

    public ConfirmScreen(WarpServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be undone";
    }
}
