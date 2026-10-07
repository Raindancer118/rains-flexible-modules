package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /roast [player]} — a random roast of somebody, in chat as you; without a name, of yourself. */
public final class RoastCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public RoastCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "roasts somebody, or yourself, in chat";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player by)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length > 1) {
            live.messages().send(by, "essentials.usage", "usage", "/roast [player]");
            return;
        }
        Player target = by;
        if (args.length == 1) {
            List<Player> found = Players.online(live.messages(), live.server(), by, args[0], true,
                    "essentials.no-such-player");
            if (found.isEmpty()) {
                return;
            }
            target = found.getFirst();
        }
        live.fun().roast(by, target);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            EssentialsServices live = services.get();
            String typed = args.length == 1 ? args[0] : "";
            return Players.suggest(live.server(), source.getSender(), typed, live.core().vanish());
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.ROAST;
    }
}
