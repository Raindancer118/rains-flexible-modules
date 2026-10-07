package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.SkyTokenService.Kind;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /token <sky|dawn> [player] [amount]}: an op hands out clear-sky or dawn tokens. */
public final class TokenCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public TokenCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "hands out clear-sky and dawn tokens";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        Optional<Kind> kind = args.length >= 1 ? Kind.of(args[0]) : Optional.empty();
        if (kind.isEmpty() || args.length > 3) {
            live.messages().send(sender, "essentials.usage", "usage", "/token <sky|dawn> [player] [amount]");
            return;
        }
        Player to = args.length >= 2 ? live.server().getPlayerExact(args[1])
                : sender instanceof Player self ? self : null;
        if (to == null) {
            live.messages().send(sender, args.length >= 2 ? "essentials.tokens.not-online" : "essentials.only-a-player",
                    "player", args.length >= 2 ? args[1] : "");
            return;
        }
        int amount = 1;
        if (args.length == 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "essentials.usage", "usage", "/token <sky|dawn> [player] [amount]");
                return;
            }
        }
        live.skyTokens().give(sender, to, kind.get(), amount);
    }

    /** Only ops hand them out; using one needs nothing. */
    @Override
    public String permission() {
        return PermissionNodes.TOKENS_GIVE;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PermissionNodes.TOKENS_GIVE)) {
            return List.of();
        }
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("sky", "dawn").stream().filter(word -> word.startsWith(typed)).toList();
        }
        if (args.length == 2) {
            EssentialsServices live = services.get();
            return Players.suggest(live.server(), sender, args[1], live.core().vanish());
        }
        return List.of();
    }
}
