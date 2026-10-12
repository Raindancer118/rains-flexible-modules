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

/** {@code /msg <player> <text>} — a private message, to a name or to a nickname. */
public final class MsgCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public MsgCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "sends somebody a private message";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player from)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(from, "essentials.usage", "usage", "/msg <player> <message>");
            return;
        }
        String text = String.join(" ", List.of(args).subList(1, args.length));
        if (live.server().getPlayerExact(args[0]) == null) {
            java.util.Optional<String> there = live.core().playersElsewhere().stream()
                    .filter(name -> name.equalsIgnoreCase(args[0])).findFirst();
            if (there.isPresent()) {
                live.messaging().sendAcross(from, there.get(), text);
                return;
            }
        }
        List<Player> found = Players.online(live.messages(), live.server(), from, args[0], true,
                "essentials.no-such-player");
        if (found.isEmpty()) {
            return;
        }
        live.messaging().send(from, found.getFirst(), text);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            EssentialsServices live = services.get();
            String typed = args.length == 1 ? args[0] : "";
            List<String> here = Players.suggest(live.server(), source.getSender(), typed, live.core().vanish());
            List<String> all = new java.util.ArrayList<>(here);
            live.core().playersElsewhere().stream()
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed.toLowerCase(java.util.Locale.ROOT)))
                    .filter(name -> !all.contains(name)).sorted().forEach(all::add);
            return all;
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.MSG;
    }
}
