package de.raindancer.modules.moderation.command;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.FineService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /debt} — what you owe from fines, and {@code /debt pay [amount|all]} to pay some or all of it. */
public final class DebtCommand implements IModerationCommand {

    /** Everybody has it by default; held rather than granted, like reporting. */
    public static final String USE = ModerationPermission.PREFIX + "debt";

    private final Supplier<ModerationServices> services;

    public DebtCommand(Supplier<ModerationServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "shows what you owe from fines, and lets you pay it";
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
        FineService fines = moderation.fines();
        if (args.length == 0) {
            Money owing = fines.owed(player.getUniqueId());
            if (!owing.isPositive()) {
                moderation.messages().send(player, "moderation.debt.none");
                return;
            }
            moderation.messages().send(player, "moderation.debt.owed", "amount", Fees.format(owing));
            for (FineRecord each : fines.finesOf(player.getUniqueId())) {
                if (each.isOpen()) {
                    moderation.messages().send(player, "moderation.debt.line", "amount", Fees.format(Money.of(each.debt())),
                            "reason", each.reason());
                }
            }
            moderation.messages().send(player, "moderation.debt.how-to-pay");
            return;
        }
        if (!args[0].equalsIgnoreCase("pay")) {
            moderation.messages().send(player, "moderation.usage", "usage", "/debt  ·  /debt pay [amount|all]");
            return;
        }
        Money wanted = null;
        if (args.length > 1 && !args[1].equalsIgnoreCase("all")) {
            wanted = Fees.amount(args[1]);
            if (!wanted.isPositive()) {
                moderation.messages().send(player, "moderation.fine.bad-amount", "text", args[1]);
                return;
            }
        }
        FineService.Payment payment = fines.pay(player.getUniqueId(), wanted);
        switch (payment.status()) {
            case DONE -> moderation.messages().send(player, payment.remaining().isPositive()
                            ? "moderation.debt.paid-part" : "moderation.debt.paid-off",
                    "amount", Fees.format(payment.paid()), "left", Fees.format(payment.remaining()));
            case NOTHING_OWED -> moderation.messages().send(player, "moderation.debt.none");
            case NO_ECONOMY -> moderation.messages().send(player, "moderation.fine.no-economy");
            case NOT_ENOUGH -> moderation.messages().send(player, "moderation.debt.not-enough",
                    "amount", Fees.format(payment.remaining()));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return "pay".startsWith(args.length == 1 ? args[0].toLowerCase(java.util.Locale.ROOT) : "")
                    ? List.of("pay") : List.of();
        }
        return args.length == 2 && args[0].equalsIgnoreCase("pay") ? List.of("all") : List.of();
    }
}
