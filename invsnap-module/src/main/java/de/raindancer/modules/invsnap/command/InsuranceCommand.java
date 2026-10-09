package de.raindancer.modules.invsnap.command;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.service.InsuranceService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /insurance [on|off]}: opt in or out of death insurance, or see where you stand. */
public final class InsuranceCommand implements IInvSnapCommand {

    private final Supplier<InvSnapServices> services;

    public InsuranceCommand(Supplier<InvSnapServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        InvSnapServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "invsnap.only-a-player");
            return;
        }
        InsuranceService insurance = live.insurance();
        if (!insurance.enabled()) {
            live.messages().send(player, "invsnap.insurance.disabled");
            return;
        }
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (word) {
            case "on" -> {
                insurance.setInsured(player.getUniqueId(), true);
                live.messages().send(player, "invsnap.insurance.now-on", "price", price(live, player));
            }
            case "off" -> {
                insurance.setInsured(player.getUniqueId(), false);
                live.messages().send(player, "invsnap.insurance.now-off");
            }
            case "" -> live.messages().send(player,
                    insurance.isInsured(player.getUniqueId()) ? "invsnap.insurance.status-on"
                            : "invsnap.insurance.status-off",
                    "price", price(live, player));
            default -> live.messages().send(player, "invsnap.insurance.usage");
        }
    }

    private static String price(InvSnapServices live, Player player) {
        return Fees.format(Fees.quote(InsuranceService.SOURCE, live.insurance().premiumFor(player)));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return args.length == 1 ? List.of("on", "off") : List.of();
    }

    @Override
    public String describe() {
        return "opting in or out of death insurance";
    }
}
