package de.raindancer.modules.cosmetics.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command. Built at bootstrap, before the module runs, so it captures nothing and asks a supplier
 * for the services when it is run.
 */
public interface ICosmeticsCommand extends BasicCommand {

    String describe();
}
