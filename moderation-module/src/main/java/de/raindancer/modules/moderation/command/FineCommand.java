package de.raindancer.modules.moderation.command;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.FineService;
import de.raindancer.modules.moderation.util.FineTalk;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /fine <player> <amount> <reason> [victim:<name>]}, {@code /fine forgive <player>} and
 * {@code /fine revoke <player>}.
 *
 * <p>A fine is charged before it is written down: somebody who cannot be charged is not fined. Offline players
 * with an account can be fined too.
 */
public final class FineCommand extends StaffCommand {

    public FineCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.FINE);
    }

    @Override
    public String describe() {
        return "fines somebody, or forgives or revokes what they were fined";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length >= 2 && (args[0].equalsIgnoreCase("forgive") || args[0].equalsIgnoreCase("revoke"))) {
            Optional<OfflinePlayer> found = subject(sender, args[1]);
            if (found.isPresent() && mayAct(sender, found.get().getUniqueId())) {
                if (args[0].equalsIgnoreCase("forgive")) {
                    forgive(sender, found.get());
                } else {
                    revoke(sender, found.get());
                }
            }
            return;
        }
        if (args.length < 3) {
            moderation.messages().send(sender, "moderation.usage", "usage",
                    "/fine <player> <amount> <reason> [victim:<name>]  ·  /fine forgive|revoke <player>");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        if (!mayAct(sender, them.getUniqueId())) {
            return;
        }
        Money amount = Fees.amount(args[1]);
        if (!amount.isPositive()) {
            moderation.messages().send(sender, "moderation.fine.bad-amount", "text", args[1]);
            return;
        }
        List<String> words = new ArrayList<>(List.of(args).subList(2, args.length));
        UUID victim = null;
        if (words.size() > 1 && words.getLast().toLowerCase(java.util.Locale.ROOT).startsWith("victim:")) {
            String named = words.removeLast().substring("victim:".length());
            Optional<OfflinePlayer> who = Players.one(moderation.messages(), moderation.server(), sender, named);
            if (who.isEmpty()) {
                return;
            }
            victim = who.get().getUniqueId();
            if (moderation.config().victimSharePercent() <= 0) {
                moderation.messages().send(sender, "moderation.fine.victim-off");
            }
        }
        FineService.Result result = moderation.fines().fine(actorOf(sender), actorNameOf(sender),
                them.getUniqueId(), Players.nameOf(them), amount, String.join(" ", words).trim(), victim,
                FineService.Kind.FINE);
        FineTalk.tell(moderation.messages(), sender, Players.nameOf(them), result);
    }

    private void forgive(CommandSender sender, OfflinePlayer them) {
        ModerationServices moderation = services();
        Money wiped = moderation.fines().forgive(actorOf(sender), actorNameOf(sender), them.getUniqueId(),
                Players.nameOf(them));
        moderation.messages().send(sender, wiped.isPositive() ? "moderation.fine.forgiven" : "moderation.fine.nothing-to-forgive",
                "player", Players.nameOf(them), "amount", Fees.format(wiped));
    }

    private void revoke(CommandSender sender, OfflinePlayer them) {
        ModerationServices moderation = services();
        Optional<FineRecord> latest = moderation.fines().finesOf(them.getUniqueId()).stream()
                .filter(each -> !each.revoked()).findFirst();
        if (latest.isEmpty()) {
            moderation.messages().send(sender, "moderation.fine.no-fines", "player", Players.nameOf(them));
            return;
        }
        FineTalk.revoked(moderation, sender, actorOf(sender), actorNameOf(sender), latest.get(), Players.nameOf(them));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1) {
            List<String> first = new ArrayList<>(super.suggest(source, args));
            for (String word : List.of("forgive", "revoke")) {
                if (word.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    first.add(word);
                }
            }
            return first;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("forgive") || args[0].equalsIgnoreCase("revoke"))) {
            return Players.suggest(services().server(), source.getSender(), args[1]);
        }
        return List.of();
    }
}
