package de.raindancer.modules.warp;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/** A bank in a map, installed as the server's economy for the length of one test. */
public final class FakeEconomy implements Economy {

    private final Map<UUID, Money> balances = new HashMap<>();

    public static FakeEconomy install() {
        FakeEconomy bank = new FakeEconomy();
        Economies.provide(mock(Plugin.class), bank);
        return bank;
    }

    public static void uninstall() {
        Economies.clear();
    }

    public FakeEconomy give(UUID who, String written) {
        balances.put(who, de.raindancer.core.social.economy.Fees.amount(written));
        return this;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Currency currency() {
        return Currency.DEFAULT;
    }

    @Override
    public boolean hasAccount(UUID player) {
        return true;
    }

    @Override
    public Money balance(UUID player) {
        return balances.getOrDefault(player, Money.ZERO);
    }

    @Override
    public EconomyResult deposit(UUID player, Money amount, String reason) {
        Money now = balance(player).plus(amount);
        balances.put(player, now);
        return EconomyResult.done(amount, now);
    }

    @Override
    public EconomyResult withdraw(UUID player, Money amount, String reason) {
        if (!balance(player).isAtLeast(amount)) {
            return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
        }
        Money now = balance(player).minus(amount);
        balances.put(player, now);
        return EconomyResult.done(amount, now);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
        EconomyResult taken = withdraw(from, amount, reason);
        if (!taken.succeeded()) {
            return taken;
        }
        return deposit(to, amount, reason);
    }
}
