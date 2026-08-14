package de.raindancer.modules.claims.extension;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.screen.ClaimMenu;
import org.bukkit.entity.Player;

/**
 * Lets an unrelated module put a button on {@link ClaimMenu} without this module ever knowing that
 * module exists — the same direction every other cross-module dependency in this reactor runs: the
 * other side compiles against {@code claims-module}, never the other way round.
 *
 * <h2>Why a registry rather than claims-module reaching out</h2>
 * A claim's page is drawn once per open and has no idea what else might want a say in it. Anything
 * that does — a training-mannequin count, one day a shop, a quest board — registers itself here, and
 * {@link ClaimMenu} asks the registry rather than any specific module. Adding a tenth contributor
 * changes nothing here.
 *
 * <h2>Deliberately not in {@code screen}</h2>
 * This is not a screen and opens nothing on its own — it lives in its own package so {@code
 * ScreenGrammarTest}'s scan of the {@code screen} folder, which expects everything there to be a menu
 * something else constructs, does not have to carve out an exception for the one file in it that is not.
 *
 * @see ClaimMenuExtensions
 */
public interface ClaimMenuExtension {

    /**
     * What to draw for this claim and viewer, or {@code null} to draw nothing.
     *
     * <p>Asked fresh on every render — the same reason every other button on this page is computed
     * rather than cached: a count in the lore has to say what is true right now, not what was true
     * when the page was first opened.
     *
     * @param parent this page itself, for a submenu to open with — so Back leads here rather than
     *               nowhere, the same rule every other button on this page already follows
     */
    ClaimMenuButton contribute(ClaimServices services, Claim claim, Player viewer, Menu parent);
}
