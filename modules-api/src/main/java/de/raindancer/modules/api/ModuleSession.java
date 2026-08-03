package de.raindancer.modules.api;

/**
 * A module's context plus the way to take it all back.
 *
 * <p>Two interfaces rather than one because they have different audiences. A module sees a
 * {@link ModuleContext} and can register things through it; only the host sees the session, and only the
 * host may unwind it. A module able to unwind its own registrations could remove another module's
 * listeners by mistake, and there would be nothing to stop it.
 */
public interface ModuleSession {

    /** What the module is given. */
    ModuleContext context();

    /**
     * Undo everything registered through the context: listeners unregistered, resources closed, in the
     * reverse order they arrived.
     *
     * <p>Called by the host after {@code disable()} — and also when {@code enable()} threw, which is the
     * case that matters, because a module that failed halfway has usually registered a listener already.
     * Must not throw, and must be safe to call twice.
     */
    void unwind();
}
