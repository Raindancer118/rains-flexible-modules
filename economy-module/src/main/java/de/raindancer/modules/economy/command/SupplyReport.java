package de.raindancer.modules.economy.command;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.MoneySupply;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Flows;
import de.raindancer.modules.economy.service.SupplyService.Health;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** How much money there is, where it came from this week and where it went — /eco health and /treasury. */
final class SupplyReport {

    private static final int LINES = 6;

    private SupplyReport() {
    }

    static void send(EconomyServices live, CommandSender to, Health health) {
        Currency currency = live.currency();
        var messages = live.messages();
        MoneySupply supply = health.supply();
        messages.send(to, "economy.health.title");
        messages.send(to, "economy.health.money", "amount", currency.render(supply.circulating()));
        if (supply.capped()) {
            messages.send(to, "economy.health.capped", "cap", currency.render(supply.cap()),
                    "treasury", currency.render(supply.treasury()), "percent", percent(supply.fill() * 100));
            if (supply.overCap().isPositive()) {
                messages.send(to, "economy.health.over-cap", "amount", currency.render(supply.overCap()));
            }
        } else {
            messages.send(to, "economy.health.open");
        }
        messages.send(to, "economy.health.per-player", "count", String.valueOf(supply.activePlayers()),
                "amount", currency.render(supply.perActivePlayer()));
        health.moneyGrowthPercent().ifPresent(growth ->
                messages.send(to, "economy.health.growth", "percent", signed(growth)));
        if (health.weeklyInflation().isPresent()) {
            messages.send(to, "economy.health.inflation", "percent", signed(health.weeklyInflation().getAsDouble()),
                    "level", String.format(Locale.ROOT, "%.2f", health.level()));
        } else {
            messages.send(to, "economy.health.no-inflation");
        }
        messages.send(to, "economy.health.taps", "faucet", signed(health.faucetPercent()),
                "sink", signed(health.sinkPercent()), "stab-faucet", signed(health.taps().faucet()),
                "stab-sink", signed(health.taps().sink()));
        flows(live, to, "economy.health.created", health.week().totalCreated(), health.week().created(), currency);
        flows(live, to, "economy.health.destroyed", health.week().totalDestroyed(), health.week().destroyed(), currency);
        live.funds().boosting().forEach(fund -> live.funds().effectOf(fund).ifPresent(effect -> {
            if (effect instanceof de.raindancer.modules.economy.rules.FundRule.Effect.Boost boost) {
                messages.send(to, "economy.fund.boosting", "name", fund.name(), "percent",
                        String.valueOf(boost.percent()));
            }
        }));
    }

    private static void flows(EconomyServices live, CommandSender to, String key, Money total, Map<String, Long> flows,
                              Currency currency) {
        live.messages().send(to, key, "amount", currency.render(total));
        List<Map.Entry<String, Long>> ranked = Flows.ranked(flows);
        if (ranked.isEmpty()) {
            live.messages().send(to, "economy.health.none");
            return;
        }
        for (Map.Entry<String, Long> each : ranked.subList(0, Math.min(LINES, ranked.size()))) {
            live.messages().send(to, "economy.health.line", "label", each.getKey(),
                    "amount", currency.render(Money.of(each.getValue())));
        }
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
