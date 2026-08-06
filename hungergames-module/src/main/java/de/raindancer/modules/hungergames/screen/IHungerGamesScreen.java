package de.raindancer.modules.hungergames.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Everything worth saying about a menu is already said by extending Core's {@code Menu}: the layout, the
 * chrome, the click handling and the window title are all its. What this adds is the module's grammar, which
 * {@code ScreenGrammarTest} holds against the source:
 *
 * <ul>
 *   <li><b>Greyed, never hidden.</b> A button somebody may not use is shown with the reason. This module has
 *       the largest menu in the repository — the admin suite is where a whole tournament is run from — and a
 *       page whose shape depends on who is looking is a page nobody can be talked through over voice while
 *       forty people wait.</li>
 *   <li><b>An invisible modifier is an unused modifier.</b> A screen that reads a right or shift click says so
 *       in the lore of the button that reads it. Earns its keep here: the config page uses a right click for a
 *       round-only override, which is a different thing from what the left click does to the same setting.</li>
 *   <li><b>Nothing irreversible without a confirmation.</b> In this module that is not "delete a warp". It is
 *       starting the round, ending it, calling the deathmatch and eliminating a tribute by hand — every one of
 *       them in front of an audience, and none of them undoable.</li>
 *   <li><b>Buttons come from Core's {@code Icons}</b>, or the server grows two ideas of what a button looks
 *       like.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a gamemaster presses four more
 *       times while the server watches.</li>
 * </ul>
 */
public interface IHungerGamesScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen is for, for a diagnostic that lists them. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
