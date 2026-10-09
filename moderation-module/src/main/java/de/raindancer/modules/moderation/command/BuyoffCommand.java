package de.raindancer.modules.moderation.command;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.BuyoffService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /buyoff} — what ending your temporary mute now would cost, and {@code /buyoff confirm} to pay it.
 *
 * <p>Two steps on purpose: the price is shown before a coin moves, and the confirmation says what it was.
 */
public final class BuyoffCommand implements IModerationCommand {

    public static final String USE = ModerationPermission.PREFIX + "buyoff";

    private final Supplier<ModerationServices> services;

    public BuyoffCommand(Supplier<ModerationServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "ends your temporary mute early, for a price per hour left";
    }

    @Override
    public String permission() {
        return USE;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(USE);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services.get();
        if (!(sender instanceof Player player)) {
            moderation.messages().send(sender, "moderation.only-a-player");
            return;
        }
        BuyoffService buyoffs = moderation.buyoffs();
        boolean confirmed = args.length > 0 && args[0].equalsIgnoreCase("confirm");
        if (!confirmed) {
            Verdict verdict = buyoffs.mayBuyOff(player.getUniqueId());
            if (verdict.isRefused()) {
                refuse(moderation, player, verdict);
                return;
            }
            BuyoffService.Quote quote = buyoffs.quote(player.getUniqueId()).orElseThrow();
            moderation.messages().send(player, "moderation.buyoff.quote", "price", Fees.format(quote.price()),
                    "hours", quote.hours());
            return;
        }
        BuyoffService.Outcome outcome = buyoffs.buyOff(player.getUniqueId(), player.getName());
        switch (outcome.status()) {
            case DONE -> moderation.messages().send(player, "moderation.buyoff.done", "price",
                    Fees.format(outcome.payment().amount()));
            case REFUSED -> refuse(moderation, player, outcome.verdict());
            case CANNOT_PAY -> moderation.messages().send(player, "moderation.buyoff.cannot-pay", "price",
                    Fees.format(outcome.quote().price()));
            case FAILED -> moderation.messages().send(player, "moderation.buyoff.failed");
        }
    }

    private static void refuse(ModerationServices moderation, Player player, Verdict verdict) {
        moderation.messages().send(player, verdict.reason(), "detail", verdict.detail() == null ? "" : verdict.detail());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("confirm") : List.of();
    }
}
