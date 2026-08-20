package de.raindancer.modules.xaeromap.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <p>Built during Paper's bootstrap phase, long before the module enables, so it captures nothing the
 * module builds and holds a supplier instead — see {@code IInvSnapCommand} for the full reasoning. The
 * host wraps every one of these in {@code ModuleCommands.guarded}.
 */
public interface IXaeroMapCommand extends BasicCommand {

    /** What this command is for, shown in the diagnostic that lists them. */
    String describe();
}
