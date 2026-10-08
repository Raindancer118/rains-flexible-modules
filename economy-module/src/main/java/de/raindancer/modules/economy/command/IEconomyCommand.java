package de.raindancer.modules.economy.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module. Built at bootstrap, before the module runs, so it captures nothing
 * and looks its services up through a supplier when it is actually used.
 */
public interface IEconomyCommand extends BasicCommand {

    String describe();
}
