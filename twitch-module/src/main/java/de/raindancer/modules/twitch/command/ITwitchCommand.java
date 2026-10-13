package de.raindancer.modules.twitch.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/** A command belonging to this module; built at bootstrap, so it holds a supplier, never the module's state. */
public interface ITwitchCommand extends BasicCommand {

    String describe();
}
