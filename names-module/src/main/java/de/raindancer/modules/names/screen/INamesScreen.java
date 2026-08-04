package de.raindancer.modules.names.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}: the layout,
 * the chrome, the click handling and the window title are all its. What this adds is the module's own
 * grammar, which {@code ScreenGrammarTest} holds against the source and which this names so it can be
 * found:
 *
 * <ul>
 *   <li><b>Greyed, never hidden.</b> A button somebody may not use is shown with the reason. Hiding
 *       makes the menu a different shape per viewer, so nobody can be told "the third one along".</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift click
 *       says so in the lore of the button that reads it.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b> The danger slot is flanked by navigation, so
 *       a misclick has to cost a page rather than the thing.</li>
 *   <li><b>Buttons come from Core's {@code Icons}</b>, or the server grows two ideas of what a button
 *       looks like.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four more
 *       times.</li>
 * </ul>
 *
 * <p>This module's screens have one rule of their own on top: <b>a reagent is shown in itself.</b> The
 * icon is the item you craft with, and its name is painted in what that item does — because "pale blue"
 * and "light blue" are the same two words to everybody who has not seen them side by side.
 */
public interface INamesScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
