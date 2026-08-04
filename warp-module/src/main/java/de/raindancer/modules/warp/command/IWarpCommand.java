package de.raindancer.modules.warp.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <h2>The one thing every command here has to get right</h2>
 * It is built during Paper's bootstrap phase, long before the module enables — the {@code COMMANDS}
 * lifecycle event fires then, and a handler registered in {@code onEnable} never runs at all,
 * silently. So a command <b>must not capture anything the module builds</b>; it holds a supplier and
 * asks when it is run.
 *
 * <p>Which means the state to design for is "command registered, module not running": somebody can
 * type it before the module has started, after it failed to start, and after it has stopped. The host
 * wraps every one of these in {@code ModuleCommands.guarded}, so that answers with one red line
 * naming the module rather than a {@link NullPointerException} in the console.
 *
 * <h2>What earns a subcommand here</h2>
 * Typing it beats clicking, or it takes an argument a menu cannot ask for. So {@code /warp <name>}
 * earns its place — somebody who knows where they are going should not have to open a window — and
 * so does {@code set}, which needs a name. Choosing an icon does not: that is a chooser.
 */
public interface IWarpCommand extends BasicCommand {

    /** What this command is for, shown in help and in the diagnostic that lists them. */
    String describe();
}
