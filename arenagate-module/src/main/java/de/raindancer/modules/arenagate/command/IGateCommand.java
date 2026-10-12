package de.raindancer.modules.arenagate.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/** A command of this module, built at bootstrap: it captures nothing and holds a supplier. */
public interface IGateCommand extends BasicCommand {

    String describe();
}
