package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>The page is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the right, the
 * consequences on the middle button. It used to be written out here, identically in the claims
 * module, and again in the warps module — three copies of the page that guards every irreversible
 * button, which is three places to fix the next thing in one of.
 *
 * <p>The arrangement has to be the same everywhere or it does not work at all: left and right are a
 * habit people build, and a dialog that swaps them is one they learn to click through and then get
 * wrong exactly once — here, on the button that bans somebody.
 *
 * <h2>The one thing this page says differently</h2>
 * The closing line. A punishment is not undone by saying no — it goes on the record either way, and
 * that is the thing a moderator needs reminding of. "This cannot be undone" would be the wrong
 * sentence, and a wrong sentence on a confirmation is worse than none, so Core takes the line as an
 * argument and this is what passes it.
 */
public final class ConfirmScreen extends ConfirmMenu implements IModerationScreen {

    private static final String ON_THE_RECORD_EITHER_WAY =
            "<dark_gray>It goes on their record either way.";

    public ConfirmScreen(ModerationServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, ON_THE_RECORD_EITHER_WAY,
                onYes);
    }

    @Override
    public String describe() {
        return "a confirmation, so a misclick costs a page rather than the thing";
    }
}
