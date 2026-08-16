package de.raindancer.modules.invsnap.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <p>Built during Paper's bootstrap phase, long before the module enables, so it must not capture
 * anything the module builds — see {@code MannequinCommand}'s {@code IMannequinCommand} for the
 * full reasoning. The host wraps every one of these in {@code ModuleCommands.guarded}.
 */
public interface IInvSnapCommand extends BasicCommand {

    /** What this command is for, shown in the diagnostic that lists them. */
    String describe();
}
