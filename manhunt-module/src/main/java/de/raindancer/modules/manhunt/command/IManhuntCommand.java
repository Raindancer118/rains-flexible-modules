package de.raindancer.modules.manhunt.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module — see {@code chained-module}'s own {@code IChainedCommand} for
 * why it must not capture anything the module builds and instead asks for it, through a supplier,
 * every time it runs.
 */
public interface IManhuntCommand extends BasicCommand {

    /** What this command is for, shown in help and in the diagnostic that lists them. */
    String describe();
}
