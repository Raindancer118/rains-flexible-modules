package de.raindancer.modules.moderation.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}: the layout,
 * the chrome, the click handling and the window title are all its. What this adds is the module's own
 * grammar, which {@code ScreenGrammarTest} holds against the source and which this names so it can be
 * found:
 *
 * <ul>
 *   <li><b>Buttons somebody may not use are shown, greyed, with the reason</b> — never hidden. Hiding
 *       makes the menu a different shape per viewer, so nobody can be told "the third one along", and
 *       "why can I not see it" has no answer on screen. This matters more here than anywhere else in
 *       the repository: half the buttons on a moderation screen are things the viewer may not do, and
 *       a trial helper needs to be able to see what they will get next.</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift click
 *       says so in the lore of the button that reads it.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b> The danger slot is flanked by navigation,
 *       so a misclick has to cost a page rather than a permanent ban.</li>
 *   <li><b>Buttons come from Core's {@code Icons}</b>, or the server grows two ideas of what a button
 *       looks like — which is how five plugins came to look like five plugins.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four more
 *       times.</li>
 * </ul>
 */
public interface IModerationScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
