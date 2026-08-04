package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>The page itself is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the right, the
 * consequences on the middle button. It used to be written out here, and identically in the
 * moderation module, and again in the warps module. Three copies of the page that guards every
 * irreversible button is three places to fix the next thing in one of, and the copy nobody fixes is
 * always the one in front of a delete.
 *
 * <p>It also has to be the same everywhere to work at all: left and right are a habit people build,
 * and a dialog that swaps them is one they learn to click through and then get wrong exactly once.
 * Core pins that in {@code ConfirmMenuGrammarTest}.
 *
 * <p>What is left here is the constructor this module's call sites already use, and the name, which
 * is what {@code ScreenGrammarTest} looks for when it checks that every {@code danger(} button on
 * every page confirms first.
 */
public final class ConfirmScreen extends ConfirmMenu implements IClaimScreen {

    /**
     * @param claim what is being confirmed, for symmetry with every other claim screen's
     *              constructor. Unused here: the question and the consequences already name it, in
     *              the words the caller chose
     */
    public ConfirmScreen(ClaimServices services, Player viewer, Claim claim, Menu parent,
                         String question, List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "a confirmation, so a misclick costs a page rather than the thing";
    }
}
