package de.raindancer.modules.tpa.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}: the layout,
 * the chrome, the click handling and the window title are all its. What this adds is the module's
 * grammar, which {@code ScreenGrammarTest} holds against the source:
 *
 * <ul>
 *   <li><b>Greyed, never hidden.</b> A button somebody may not use is shown with the reason.</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift click
 *       says so in the lore of the button that reads it — and these screens read a great many of
 *       them, because a face is one button that can mean three things.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b></li>
 *   <li><b>Buttons come from Core's {@code Icons}.</b></li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four
 *       more times.</li>
 * </ul>
 */
public interface ITpaScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
