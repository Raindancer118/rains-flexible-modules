package de.raindancer.modules.economy;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.service.BankClient;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * What the economy module has on a server that is not the network's bank: no ledger and no shop, only the way to the
 * bank — {@code /balance}, {@code /pay} and {@code /baltop}.
 *
 * @param startedAsClient the module began as a client and so never opened a ledger; if the bank is moved here later
 *                        it needs a restart to be one
 */
public record EconomyClient(Plugin plugin, Server server, Messages messages, RainsCore core, BankClient bank,
                            boolean startedAsClient) {

    /** Commands go to the bank rather than to a ledger here. */
    public boolean active() {
        return startedAsClient || bank.elsewhere();
    }

    /** The bank is another server, so there is somebody to ask. */
    public boolean usable() {
        return bank.elsewhere();
    }

    /** Says why a command of the bank's cannot be run here. */
    public void refuse(CommandSender sender) {
        if (bank.keepsIt()) {
            messages.send(sender, "economy.client.restart");
        } else {
            messages.send(sender, "economy.client.on-the-bank", "server", bank.bankName());
        }
    }
}
