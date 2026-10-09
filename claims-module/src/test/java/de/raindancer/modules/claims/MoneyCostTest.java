package de.raindancer.modules.claims;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.service.MoneyToll;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MoneyCostTest {

    private final UUID visitor = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final Plugin plugin = mock(Plugin.class);
    private final Bank bank = new Bank();

    static final class Bank implements Economy {
        final Map<UUID, Long> balances = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean refuseTransfers;

        @Override public String name() { return "test"; }
        @Override public Currency currency() { return Currency.DEFAULT; }
        @Override public boolean hasAccount(UUID player) { return true; }
        @Override public Money balance(UUID player) { return Money.of(balances.getOrDefault(player, 0L)); }
        @Override public EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }
        @Override public EconomyResult deposit(UUID player, Money amount, String reason, String source) {
            calls.add("deposit " + amount.minor() + " " + source);
            return deposit(player, amount, reason);
        }
        @Override public EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (balance(player).minor() < amount.minor()) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            balances.merge(player, -amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }
        @Override public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            EconomyResult result = withdraw(player, amount, reason);
            calls.add("withdraw " + amount.minor() + " " + source + " " + result.outcome());
            return result;
        }
        @Override public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            if (refuseTransfers) {
                return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, balance(from));
            }
            EconomyResult taken = withdraw(from, amount, reason);
            if (!taken.succeeded()) {
                return taken;
            }
            deposit(to, amount, reason);
            calls.add("transfer " + amount.minor());
            return EconomyResult.done(amount, balance(from));
        }
    }

    @BeforeEach
    void economy() {
        Economies.provide(plugin, bank);
    }

    @AfterEach
    void reset() {
        Economies.clear();
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("money is a cost type, last in the cycle, and answers to its key")
    void moneyIsACostType() {
        assertThat(CostType.XP_POINTS.next()).isEqualTo(CostType.MONEY);
        assertThat(CostType.MONEY.next()).isEqualTo(CostType.NONE);
        assertThat(CostType.byKey("money")).hasValue(CostType.MONEY);
        assertThat(CostType.MONEY.key()).isEqualTo("money");
    }

    @Test
    @DisplayName("a money cost is whole currency units, charged with the claims.create source")
    void creationIsCharged() {
        bank.balances.put(visitor, 10_000L);
        CostService.Charge charge = new CostService().charge(player(visitor), CostType.MONEY, 25, null);
        assertThat(charge.success()).isTrue();
        assertThat(bank.calls).containsExactly("withdraw 2500 claims.create DONE");
        assertThat(bank.balances.get(visitor)).isEqualTo(7_500L);
    }

    @Test
    @DisplayName("who cannot afford it is refused with what is missing, and nothing is taken")
    void cannotAfford() {
        bank.balances.put(visitor, 1_000L);
        CostService costs = new CostService();
        CostService.Charge charge = costs.charge(player(visitor), CostType.MONEY, 25, null);
        assertThat(charge.success()).isFalse();
        assertThat(charge.shortfallDescription()).isNotBlank();
        assertThat(bank.balances.get(visitor)).isEqualTo(1_000L);
        assertThat(costs.canAfford(player(visitor), CostType.MONEY, 25, null)).isFalse();
        assertThat(costs.canAfford(player(visitor), CostType.MONEY, 10, null)).isTrue();
    }

    @Test
    @DisplayName("with no economy a money cost is refused, never waved through for free")
    void noEconomyRefuses() {
        Economies.clear();
        CostService costs = new CostService();
        assertThat(costs.charge(player(visitor), CostType.MONEY, 25, null).success()).isFalse();
        assertThat(costs.canAfford(player(visitor), CostType.MONEY, 25, null)).isFalse();
    }

    @Test
    @DisplayName("an entry fee goes to the owner in full when the server takes no cut")
    void tollWithoutCut() {
        bank.balances.put(visitor, 10_000L);
        MoneyToll.Result result = MoneyToll.pay(visitor, owner, 10, 0);
        assertThat(result.paid()).isTrue();
        assertThat(bank.balances.get(visitor)).isEqualTo(9_000L);
        assertThat(bank.balances.get(owner)).isEqualTo(1_000L);
        assertThat(bank.calls).doesNotContain("withdraw 0 claims.entry-fee-cut DONE");
    }

    @Test
    @DisplayName("the server's cut is taken from the visitor as a sink and never reaches the owner")
    void tollWithCut() {
        bank.balances.put(visitor, 10_000L);
        MoneyToll.Result result = MoneyToll.pay(visitor, owner, 10, 25);
        assertThat(result.paid()).isTrue();
        assertThat(bank.balances.get(owner)).isEqualTo(750L);
        assertThat(bank.balances.get(visitor)).isEqualTo(9_000L);
        assertThat(bank.calls).contains("withdraw 250 claims.entry-fee-cut DONE", "transfer 750");
    }

    @Test
    @DisplayName("a visitor who cannot cover the whole fee pays nothing at all")
    void tollNeedsTheWholeFee() {
        bank.balances.put(visitor, 900L);
        MoneyToll.Result result = MoneyToll.pay(visitor, owner, 10, 25);
        assertThat(result.paid()).isFalse();
        assertThat(result.shortfall()).isNotBlank();
        assertThat(bank.balances.get(visitor)).isEqualTo(900L);
        assertThat(bank.balances.get(owner)).isNull();
    }

    @Test
    @DisplayName("if the owner cannot be paid the cut is given back")
    void tollRefundsTheCutWhenTheTransferFails() {
        bank.balances.put(visitor, 10_000L);
        bank.refuseTransfers = true;
        MoneyToll.Result result = MoneyToll.pay(visitor, owner, 10, 25);
        assertThat(result.paid()).isFalse();
        assertThat(bank.balances.get(visitor)).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("land without an owner: the whole fee is a sink")
    void tollWithoutOwner() {
        bank.balances.put(visitor, 10_000L);
        assertThat(MoneyToll.pay(visitor, null, 10, 0).paid()).isTrue();
        assertThat(bank.balances.get(visitor)).isEqualTo(9_000L);
        assertThat(bank.calls).containsExactly("withdraw 1000 claims.entry-fee DONE");
    }

    private ClaimService service(ClaimSettings settings, ClaimRegistry registry) {
        ClaimRightsRule rights = mock(ClaimRightsRule.class);
        return new ClaimService(plugin, registry, mock(ZoneRegistry.class), mock(ClaimStorage.class),
                settings, new CostService(), rights);
    }

    @Test
    @DisplayName("shrinking a money-paid claim refunds the owner's balance at the shrink rate, not the bank")
    void shrinkRefundsMoney() {
        UUID id = UUID.randomUUID();
        Claim claim = new Claim(UUID.randomUUID(), "home", UUID.randomUUID(), "world",
                ClaimShape.rectangle(0, 0, 31, 31, 0, 64), id);
        claim.recordPayment(CostType.MONEY, 100, 1024, null);
        ClaimService service = service(ClaimSettings.DEFAULTS.withShrinkRefundRate(0.5), new ClaimRegistry());

        ClaimService.Settlement settlement = service.settleResizeCost(player(id), claim,
                ClaimShape.rectangle(0, 0, 15, 31, 0, 64));

        assertThat(settlement.refunded()).isEqualTo(25);
        assertThat(bank.balances.get(id)).isEqualTo(2_500L);
        assertThat(claim.bank().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("growing a money-paid claim charges the difference with claims.create")
    void growChargesMoney() {
        UUID id = UUID.randomUUID();
        bank.balances.put(id, 100_000L);
        Claim claim = new Claim(UUID.randomUUID(), "home", UUID.randomUUID(), "world",
                ClaimShape.rectangle(0, 0, 15, 31, 0, 64), id);
        claim.recordPayment(CostType.MONEY, 50, 512, null);
        ClaimService service = service(ClaimSettings.DEFAULTS, new ClaimRegistry());

        ClaimService.Settlement settlement = service.settleResizeCost(player(id), claim,
                ClaimShape.rectangle(0, 0, 31, 31, 0, 64));

        assertThat(settlement.charged()).isEqualTo(50);
        assertThat(bank.calls).containsExactly("withdraw 5000 claims.create DONE");
    }

    @Test
    @DisplayName("refunding a delete returns money through the refund path, at the delete rate")
    void deleteRefundGoesToTheBalance() {
        UUID id = UUID.randomUUID();
        CostService.refundMoney(id, ClaimService.deleteRefund(40, 0.5));
        assertThat(bank.balances.get(id)).isEqualTo(2_000L);
    }
}
