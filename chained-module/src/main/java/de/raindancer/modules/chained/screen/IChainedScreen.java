package de.raindancer.modules.chained.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}. What
 * this adds is the module's grammar, which {@code ScreenGrammarTest} holds against the source:
 *
 * <ul>
 *   <li><b>Greyed, never hidden.</b> A button somebody may not use is shown with the reason.</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift
 *       click says so in the lore of the button that reads it.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b> Resetting the map is the one
 *       irreversible thing this module does, and the danger slot is flanked by navigation, so a
 *       misclick has to cost a page rather than the world.</li>
 *   <li><b>Buttons come from Core's {@code Icons}</b>, or the server grows two ideas of what a
 *       button looks like.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four
 *       more times.</li>
 * </ul>
 */
public interface IChainedScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
