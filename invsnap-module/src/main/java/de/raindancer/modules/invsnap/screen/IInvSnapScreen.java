package de.raindancer.modules.invsnap.screen;

/**
 * A screen belonging to this module.
 *
 * <p>Not a marker for its own sake — {@code describe()} is what a diagnostic names, and a screen
 * without one is a screen nobody can point at when something on it is wrong.
 */
public interface IInvSnapScreen {

    /** Opens it for whoever it was built for. Core's {@code Menu} already provides this. */
    void open();

    /** What this screen shows, for a diagnostic. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
