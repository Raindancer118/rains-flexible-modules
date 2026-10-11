package de.raindancer.modules.sends.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/** A command of this module, built at bootstrap: it captures nothing and holds a supplier. */
public interface ISendsCommand extends BasicCommand {

    String describe();
}
