package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyClient;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Collection;
import java.util.List;

/**
 * A command belonging to this module. Built at bootstrap, before the module runs, so it captures nothing
 * and looks its services up through a supplier when it is actually used.
 */
public interface IEconomyCommand extends BasicCommand {

    String describe();

    /** Run on a server that is not the bank: by default, saying that this is on the bank's server. */
    default void executeClient(EconomyClient client, CommandSourceStack source, String[] args) {
        client.refuse(source.getSender());
    }

    default Collection<String> suggestClient(EconomyClient client, CommandSourceStack source, String[] args) {
        return List.of();
    }
}
