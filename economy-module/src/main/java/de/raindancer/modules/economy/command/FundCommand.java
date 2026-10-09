package de.raindancer.modules.economy.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Fund;
import de.raindancer.modules.economy.service.FundService.Donation;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /fund} lists the community funds; {@code /fund <name> <amount>} gives toward one. */
public final class FundCommand extends EconomyCommand {

    public FundCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        if (!allowed(live, sender, PermissionNodes.FUND)) {
            return;
        }
        if (!live.supply().current().funds()) {
            live.messages().send(sender, "economy.fund.off");
            return;
        }
        if (args.length == 0) {
            list(live, sender);
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "economy.usage.fund");
            return;
        }
        String name = String.join(" ", List.of(args).subList(0, args.length - 1));
        player(live, sender).ifPresent(player -> amount(live, sender, args[args.length - 1]).ifPresent(amount ->
                Scheduling.async(live.plugin(), () -> {
                    Donation done = live.funds().donate(player.getUniqueId(), name, amount);
                    Scheduling.entity(live.plugin(), player, () -> {
                        switch (done.outcome()) {
                            case GIVEN, FILLED -> {
                                live.effects().play(player.getUniqueId(), Cues.OK);
                                live.messages().send(player, "economy.fund.given", "amount",
                                        live.currency().render(done.given()), "name",
                                        live.funds().find(name).map(Fund::name).orElse(name));
                            }
                            case OFF -> live.messages().send(player, "economy.fund.off");
                            case NO_SUCH_FUND -> live.messages().send(player, "economy.fund.no-such");
                            case NOT_ENOUGH -> live.messages().send(player, "economy.fund.not-enough");
                            case REFUSED -> live.messages().send(player, "economy.fund.refused");
                        }
                    });
                })));
    }

    static void list(EconomyServices live, CommandSender sender) {
        List<Fund> running = live.funds().running();
        if (running.isEmpty()) {
            live.messages().send(sender, "economy.fund.none");
        } else {
            live.messages().send(sender, "economy.fund.head");
            for (Fund fund : running) {
                String effect = live.funds().effectOf(fund).map(each -> switch (each) {
                    case de.raindancer.modules.economy.rules.FundRule.Effect.Boost boost ->
                            "+" + boost.percent() + "% for " + boost.hours() + "h";
                    default -> "";
                }).orElse("");
                live.messages().send(sender, "economy.fund.line", "name", fund.name(),
                        "raised", live.currency().render(fund.raised()), "target", live.currency().render(fund.target()),
                        "percent", String.valueOf((int) Math.floor(fund.progress() * 100)), "effect", effect);
            }
        }
        live.funds().boosting().forEach(fund -> live.funds().effectOf(fund).ifPresent(effect -> {
            if (effect instanceof de.raindancer.modules.economy.rules.FundRule.Effect.Boost boost) {
                live.messages().send(sender, "economy.fund.boosting", "name", fund.name(),
                        "percent", String.valueOf(boost.percent()));
            }
        }));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        EconomyServices live = live();
        if (live == null || args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return live.funds().running().stream().map(Fund::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String describe() {
        return "community funds";
    }
}
