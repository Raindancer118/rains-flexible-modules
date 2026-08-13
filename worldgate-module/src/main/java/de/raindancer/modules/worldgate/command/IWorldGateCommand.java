package de.raindancer.modules.worldgate.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <p>Built during Paper's bootstrap phase, long before the module enables, so it must not capture
 * anything the module builds — it holds a supplier and asks when it is run. The state to design for is
 * "command registered, module not running": somebody can type it before the module has started, after
 * it failed to start, and after it has stopped. The host wraps every one of these in
 * {@code ModuleCommands.guarded}, which turns that into one red line naming the module.
 */
public interface IWorldGateCommand extends BasicCommand {

    /** What this command is for, shown in the diagnostic that lists them. */
    String describe();
}
