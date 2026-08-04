package de.raindancer.modules.homes.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.homes.HomeServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>The page is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the right, the consequences
 * on the middle button. The old plugin had its own, as did claims, moderation and warps — four copies
 * of the page that guards every irreversible button, which is four places to fix the next thing in one
 * of.
 *
 * <p>The arrangement has to be the same everywhere or it does not work at all: left and right are a
 * habit people build, and a dialog that swaps them is one they learn to click through and then get
 * wrong exactly once — here, on the button that deletes the home they built a base around.
 *
 * <p>What is left is the constructor this module's call sites use, and the name, which is what
 * {@code ScreenGrammarTest} looks for when it checks that every {@code danger(} button confirms.
 */
public final class ConfirmScreen extends ConfirmMenu implements IHomeScreen {

    public ConfirmScreen(HomeServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be undone";
    }
}
