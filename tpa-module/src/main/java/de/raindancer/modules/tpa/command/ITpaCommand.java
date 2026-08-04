package de.raindancer.modules.tpa.command;

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
 * every one of these in {@code ModuleCommands.guarded}.
 *
 * <h2>Why there are so many of them</h2>
 * Because they are what people already type, and the aliases are a decade of muscle memory. The menu
 * exists for somebody who does not know them; the commands exist because {@code /tpa Bob} beats any
 * number of clicks for somebody who does.
 */
public interface ITpaCommand extends BasicCommand {

    /** What this command is for, shown in help and in the diagnostic that lists them. */
    String describe();
}
