package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /unignore <player>} — the one-way half of {@code /ignore}.
 *
 * <h2>Why this exists beside a toggling {@code /ignore}</h2>
 * Typing {@code /ignore <player>} again already takes it back off, but that only works if you
 * remember whether you already blocked them — and re-blocking somebody by mistake because you
 * forgot is the one failure a dedicated, one-direction command cannot have.
 */
public final class UnignoreCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public UnignoreCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "lets somebody message you again, if you had blocked them";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length == 0) {
            live.messages().send(who, "essentials.usage", "usage", "/unignore <player>");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(live.server(), args[0]);
        if (found.isEmpty()) {
            live.messages().send(who, "essentials.no-such-player", "player", args[0]);
            return;
        }
        UUID target = found.get().getUniqueId();
        String name = Players.nameOf(found.get());
        if (live.messaging().stopIgnoring(who, target)) {
            live.messages().send(who, "essentials.ignore.stopped", "player", name);
        } else {
            live.messages().send(who, "essentials.ignore.not-ignoring", "player", name);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        EssentialsServices live = services.get();
        Player who = source.getSender() instanceof Player player ? player : null;
        if (who == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (UUID id : live.store().ignoredBy(who.getUniqueId())) {
            names.add(Players.nameOf(live.server().getOfflinePlayer(id)));
        }
        return names;
    }

    @Override
    public String permission() {
        return PermissionNodes.IGNORE;
    }
}
