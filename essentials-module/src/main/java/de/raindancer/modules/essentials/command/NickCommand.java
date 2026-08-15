package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /nick [name]} — sets a nickname; {@code /nick off} takes it back off. */
public final class NickCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public NickCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "sets, or removes, what you are called instead of your own name";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (!live.nicknames().isEnabled()) {
            live.messages().send(who, "essentials.nick.switched-off");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("off")) {
            live.nicknames().clear(who);
            return;
        }
        String typed = String.join(" ", args);
        String plain = Nickname.of(typed).plain();
        boolean nameInUse = Players.realNameInUse(live.server(), plain)
                && !plain.equalsIgnoreCase(who.getName());
        live.nicknames().set(who, typed, nameInUse);
    }

    @Override
    public String permission() {
        return PermissionNodes.NICK;
    }
}
