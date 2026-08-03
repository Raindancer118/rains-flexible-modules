package de.raindancer.modules.claims.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <h2>The one thing every command here has to get right</h2>
 * It is built during Paper's bootstrap phase, long before the module enables — the {@code COMMANDS} lifecycle
 * event fires then, and a handler registered in {@code onEnable} never runs at all, silently. So a command
 * <b>must not capture anything the module builds</b>; it holds a supplier and asks when it is run.
 *
 * <p>Which means the state to design for is "command registered, module not running": a player can type it
 * before the module has started, after it failed to start, and after it has stopped. The host wraps every one of
 * these in {@code ModuleCommands.guarded}, so that answers with one red line naming the module rather than a
 * {@link NullPointerException} in the console.
 *
 * <h2>And one rule about what earns a subcommand</h2>
 * Typing it is faster than clicking, or it takes an argument a menu cannot ask for. {@code trust <player>} earns
 * its place; a flag toggle does not, because a flag is a click. The command this replaced had twenty-two
 * subcommands, most of which existed only because the menus could not do the thing yet.
 */
public interface IClaimCommand extends BasicCommand {

    /** What this command is for, shown in help and in the diagnostic that lists them. */
    String describe();
}
