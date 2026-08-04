package de.raindancer.modules.homes.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <h2>The one thing every command here has to get right</h2>
 * It is built during Paper's bootstrap phase, long before the module enables — the {@code COMMANDS}
 * lifecycle event fires then, and a handler registered in {@code onEnable} never runs at all, silently.
 * So a command <b>must not capture anything the module builds</b>; it holds a supplier and asks when it
 * is run.
 *
 * <p>Which means the state to design for is "command registered, module not running": somebody can type
 * it before the module has started, after it failed to start, and after it has stopped. The host wraps
 * every one of these in {@code ModuleCommands.guarded}, so that answers with one red line naming the
 * module rather than a {@link NullPointerException} in the console.
 *
 * <h2>What earns a command here</h2>
 * All three of these take a name a menu cannot ask for, and all three are what people already type.
 * {@code /sethome base} is faster than any number of clicks, and the muscle memory for it is a decade
 * old — which is reason enough on its own not to make somebody open a window instead.
 */
public interface IHomeCommand extends BasicCommand {

    /** What this command is for, shown in help and in the diagnostic that lists them. */
    String describe();
}
