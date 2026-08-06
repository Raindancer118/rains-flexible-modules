package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name.
 *
 * <p>Everything about the page is {@code ConfirmMenu}'s: three rows, No on the left, Yes on the right, the
 * consequences spelled out on the middle button. That arrangement being identical across every plugin on the
 * server is the whole feature. A dialog that swaps the two answers is one people learn to click through, and
 * then get wrong exactly once — in front of the thing that cannot be undone.
 *
 * <h2>What this module puts behind it, and why it is the worst list in the repository</h2>
 * In the farm worlds module the danger button deletes a world. Here there are four, and none of them deletes
 * anything — which is exactly why they need guarding more, not less. Every one is <b>irreversible because it is
 * public</b>:
 *
 * <ul>
 *   <li><b>Starting the round.</b> Forty people are released from their platforms at once. There is no putting
 *       them back: they have moved, taken loot, and found each other.</li>
 *   <li><b>Ending the round.</b> Whatever was happening is over and a winner has or has not been declared, in
 *       front of everybody.</li>
 *   <li><b>Calling the deathmatch.</b> The border starts closing on a number that cannot be un-announced.</li>
 *   <li><b>Eliminating a tribute by hand.</b> A person is out of the tournament they turned up for, by a
 *       click, and the revive that undoes it does not give them back the four minutes they spent dead.</li>
 * </ul>
 *
 * <p>A misclick on any of those costs a tournament rather than a file. So the danger slot is flanked by
 * navigation, and this class exists so that the module's own {@code ScreenGrammarTest} can go on proving that
 * every {@code danger(} button on every page is guarded — a check it could not make if the pages used Core's
 * {@code ConfirmMenu} directly.
 */
public final class ConfirmScreen extends ConfirmMenu implements IHungerGamesScreen {

    public ConfirmScreen(Player viewer, Brand brand, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, brand, parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that happens in front of everybody";
    }
}
