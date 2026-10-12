package de.raindancer.modules.arena.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/** A command of this module, built at bootstrap: it captures nothing and holds a supplier. */
public interface IArenaCommand extends BasicCommand {

    String describe();
}
