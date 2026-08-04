package de.raindancer.modules.farmworld.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}: the layout, the
 * chrome, the click handling and the window title are all its. What this adds is the module's grammar, which
 * {@code ScreenGrammarTest} holds against the source:
 *
 * <ul>
 *   <li><b>Greyed, never hidden.</b> A button somebody may not use is shown with the reason, so the menu is
 *       the same shape for everybody and "the third one along" means something. Kept without exception
 *       here, unlike the warps module: a farm world is not a secret, it is one of two or three named places
 *       the whole server talks about, and hiding the donor one from somebody who hears about it every day
 *       tells them nothing except that their list is wrong.</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift click says
 *       so in the lore of the button that reads it.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b> This is the module where that rule earns its
 *       keep: the button in the danger slot of the manage page deletes three worlds. It is flanked by
 *       navigation, so a misclick has to cost a page rather than everybody's mine.</li>
 *   <li><b>Buttons come from Core's {@code Icons}</b>, or the server grows two ideas of what a button looks
 *       like.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four more
 *       times.</li>
 * </ul>
 */
public interface IFarmWorldScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
