package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.screen.BlocklistMenu;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /blocklist} — the in-game editor for the nickname blocklist. */
public final class BlocklistCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public BlocklistCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "opens the nickname blocklist editor";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        new BlocklistMenu(live, who, null).open();
    }

    @Override
    public String permission() {
        return PermissionNodes.BLOCKLIST_MANAGE;
    }
}
