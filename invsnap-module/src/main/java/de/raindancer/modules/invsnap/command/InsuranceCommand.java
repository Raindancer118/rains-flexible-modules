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

/**
 * {@code /insurance}: the Insurance screen. {@code on|off} switches death insurance; {@code item} offers the
 * item in hand, {@code item cancel} ends the policy on it.
 */
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
        if (!insurance.enabled() && !live.itemInsurance().enabled()) {
            live.messages().send(player, "invsnap.insurance.disabled");
            return;
        }
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (word) {
            case "on", "off" -> {
                if (!insurance.enabled()) {
                    live.messages().send(player, "invsnap.insurance.death-disabled");
                } else if (word.equals("on")) {
                    insurance.setInsured(player.getUniqueId(), true);
                    live.messages().send(player, "invsnap.insurance.now-on", "price", price(live, player));
                } else {
                    insurance.setInsured(player.getUniqueId(), false);
                    live.messages().send(player, "invsnap.insurance.now-off");
                }
            }
            case "item" -> item(live, player, args);
            case "" -> live.screens().insurance(player);
            default -> live.messages().send(player, "invsnap.insurance.usage");
        }
    }

    private static void item(InvSnapServices live, Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            var held = live.itemInsurance().heldPolicyOf(player);
            if (held.isPresent() && live.itemInsurance().cancel(player, held.get().id())) {
                live.messages().send(player, "invsnap.item.cancelled", "item", held.get().description());
            } else {
                live.messages().send(player, "invsnap.item.cancel-none");
            }
        } else if (args.length == 1) {
            live.screens().offerHeld(player);
        } else {
            live.messages().send(player, "invsnap.insurance.usage");
        }
    }

    private static String price(InvSnapServices live, Player player) {
        return Fees.format(Fees.quote(InsuranceService.SOURCE, live.insurance().premiumFor(player)));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length == 1) {
            return List.of("on", "off", "item");
        }
        return args.length == 2 && args[0].equalsIgnoreCase("item") ? List.of("cancel") : List.of();
    }

    @Override
    public String describe() {
        return "the Insurance screen, death insurance on or off, and insuring one item";
    }
}
