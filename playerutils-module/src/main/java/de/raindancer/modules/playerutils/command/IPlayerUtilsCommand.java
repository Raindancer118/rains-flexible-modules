package de.raindancer.modules.playerutils.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/** Built at bootstrap, captures nothing, asks a supplier for the live services. */
public interface IPlayerUtilsCommand extends BasicCommand {

    String describe();
}
