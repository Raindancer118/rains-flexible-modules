package de.raindancer.modules.api;

import java.util.List;

/**
 * A whole feature, written once.
 *
 * <p>The point of the shape is that a module never knows whether it is a plugin. Shipped through the
 * standard wrapper it is {@code RainsModeration}, with its own jar and its own data folder; hosted
 * inside {@code RainsSMPCore} it is one feature among nine, in a corner of that plugin's folder. Both
 * arrive here as the same two calls with a different {@link ModuleContext}, and there is exactly one
 * copy of the code.
 *
 * <h2>What a module may assume</h2>
 * <ul>
 *   <li>{@link #enable} is called on the server thread, once, with RainsCore already enabled.</li>
 *   <li>{@link #disable} is called if and only if {@code enable} returned without throwing.</li>
 *   <li>Listeners registered through the context are unregistered for it, and resources handed to
 *       {@code closeWith} are closed — in reverse order, and even if {@code disable} throws.</li>
 *   <li>Its {@link #commands()} are asked for during <em>bootstrap</em>, before anything is enabled and
 *       possibly before {@code Bukkit.getServer()} answers anything useful. The handlers must therefore
 *       resolve the module when they run, not when they are built.</li>
 * </ul>
 *
 * <h2>What a module must not do</h2>
 * Touch the server in its constructor. A {@code ServiceLoader} builds it during bootstrap, and a
 * constructor that reads a config or calls {@code Bukkit.getWorlds()} turns into a plugin that fails to
 * load with a stack trace naming this interface rather than the module.
 */
public interface FlexModule {

    /** What this module is called, and what it needs. Must answer before the module is enabled. */
    ModuleInfo info();

    /**
     * Start doing the thing.
     *
     * <p>Throwing is a legitimate answer — a module that cannot open its store should say so rather than
     * run half-initialised. The host marks it failed, unwinds whatever it managed to register, tells the
     * console, and carries on with the other modules.
     */
    void enable(ModuleContext context);

    /**
     * Stop doing the thing, and flush anything that has not reached the disk.
     *
     * <p>Listeners and resources registered through the context are not this method's business — the
     * host unwinds those afterwards, whether or not this throws.
     */
    void disable();

    /**
     * The commands this module wants, if any.
     *
     * <p>Called during bootstrap and possibly more than once, so it must be cheap and must not depend on
     * anything the module sets up in {@link #enable}.
     */
    default List<ModuleCommand> commands() {
        return List.of();
    }
}
