package de.raindancer.modules.essentials.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <p>Built during Paper's bootstrap phase, long before the module enables, so it must not capture
 * anything the module builds — it holds a supplier and asks when it is run. The host wraps every one
 * of these in {@code ModuleCommands.guarded}.
 */
public interface IEssentialsCommand extends BasicCommand {

    /** What this command is for, shown in the diagnostic that lists them. */
    String describe();
}
