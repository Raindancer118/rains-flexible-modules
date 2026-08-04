package de.raindancer.modules.warp.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}: the
 * layout, the chrome, the click handling and the window title are all its. What this adds is the
 * module's grammar, which {@code ScreenGrammarTest} holds against the source:
 *
 * <ul>
 *   <li><b>Greyed, never hidden.</b> A button somebody may not use is shown with the reason, so the
 *       menu is the same shape for everybody and "the third one along" means something.</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift
 *       click says so in the lore of the button that reads it.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b> The danger slot is flanked by
 *       navigation, so a misclick costs a page rather than the warp.</li>
 *   <li><b>Buttons come from Core's {@code Icons}</b>, or the server grows two ideas of what a
 *       button looks like.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four
 *       more times.</li>
 * </ul>
 *
 * <h2>The one deliberate exception, and why</h2>
 * A warp somebody may not use is <b>not shown at all</b>, rather than greyed. It is the only place
 * this module hides anything, and it is the point of the feature: greying the staff warp tells every
 * player on the server that there is a warp called {@code staffroom}, which is the half of the
 * secret that matters. {@code WarpAccessRule.maySee} and {@code mayUse} are therefore the same
 * answer, so nothing on the page can refuse after the click.
 */
public interface IWarpScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
