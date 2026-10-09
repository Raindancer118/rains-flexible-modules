package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Debts;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.store.FineLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Charging a fine against a real (fake) economy: paid, part-paid into debt, refused, shared, revoked, forgiven. */
class FineServiceTest {

    private static final UUID MOD = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();
    private static final UUID VICTIM = UUID.randomUUID();

    @TempDir
    Path folder;

    private final Map<UUID, Money> balances = new HashMap<>();
    private final List<String> recorded = new ArrayList<>();
    private final List<String> told = new ArrayList<>();
    private Economy bank;
    private FineLedger ledger;

    private static Money units(long whole) {
        return Currency.DEFAULT.ofMajor(whole);
    }

    private FineService service(ModerationSettings settings) {
        ledger = new FineLedger(folder.resolve("fines.yml"));
        StaffRule staff = new StaffRule((who, node) -> who == null || who.equals(ADMIN)
                || (who.equals(MOD) && !node.equals(ModerationPermission.FINE_UNLIMITED.node())), subject -> false);
        return new FineService(
                (actor, actorName, target, targetName, kind, reason, detail) -> {
                    recorded.add(kind + ":" + detail);
                    return new Punishment(null, target, kind, actor, reason, Instant.now(), null, null, null, null);
                },
                ledger, staff, (who, key, values) -> told.add(key), null, de.raindancer.core.platform.log.Log.of("moderation-test"), settings);
    }

    @BeforeEach
    void economy() {
        bank = new Economy() {
            @Override public String name() { return "test"; }
            @Override public Currency currency() { return Currency.DEFAULT; }
            @Override public boolean hasAccount(UUID player) { return balances.containsKey(player); }
            @Override public boolean createAccount(UUID player) { balances.putIfAbsent(player, Money.ZERO); return true; }
            @Override public Money balance(UUID player) { return balances.getOrDefault(player, Money.ZERO); }
            @Override public EconomyResult deposit(UUID player, Money amount, String reason) {
                balances.merge(player, amount, Money::plus);
                return EconomyResult.done(amount, balance(player));
            }
            @Override public EconomyResult withdraw(UUID player, Money amount, String reason) {
                if (!balance(player).isAtLeast(amount)) {
                    return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
                }
                balances.put(player, balance(player).minus(amount));
                return EconomyResult.done(amount, balance(player));
            }
            @Override public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
                EconomyResult taken = withdraw(from, amount, reason);
                if (!taken.succeeded()) {
                    return taken;
                }
                deposit(to, amount, reason);
                return taken;
            }
        };
        Economies.provide(null, bank);
    }

    @AfterEach
    void noEconomy() {
        Economies.retract(bank);
    }

    @Test
    @DisplayName("a fine somebody can pay is taken, recorded as a FINE with its amount, and owes nothing")
    void paidInFull() {
        balances.put(BO, units(1_000));
        FineService fines = service(ModerationSettings.DEFAULTS);

        FineService.Result result = fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "griefing", null, FineService.Kind.FINE);

        assertThat(result.done()).isTrue();
        assertThat(balances.get(BO)).isEqualTo(units(750));
        assertThat(recorded).hasSize(1).first().asString().startsWith("FINE:");
        assertThat(result.record().paid()).isEqualTo(units(250).minor());
        assertThat(result.record().debt()).isZero();
        assertThat(fines.amountOn(result.punishment())).isEqualTo(Fees.format(units(250)));
        assertThat(fines.owed(BO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("what the balance cannot cover becomes debt, and the account is emptied as far as it goes")
    void unpaidBecomesDebt() {
        balances.put(BO, units(100));
        FineService fines = service(ModerationSettings.DEFAULTS);

        FineService.Result result = fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "griefing", null, FineService.Kind.FINE);

        assertThat(result.done()).isTrue();
        assertThat(balances.get(BO)).isEqualTo(Money.ZERO);
        assertThat(result.charge().paid()).isEqualTo(units(100));
        assertThat(result.charge().debt()).isEqualTo(units(150));
        assertThat(fines.owed(BO)).isEqualTo(units(150));
    }

    @Test
    @DisplayName("the economy can collect the debt through Core's Debts")
    void economyCollects() {
        balances.put(BO, units(100));
        FineService fines = service(ModerationSettings.DEFAULTS);
        fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "griefing", null, FineService.Kind.FINE);
        Debts.provide(null, fines);
        try {
            assertThat(Debts.inDebt(BO)).isTrue();
            assertThat(Debts.collected(BO, units(40))).isEqualTo(units(40));
            assertThat(fines.owed(BO)).isEqualTo(units(110));
            assertThat(Debts.collected(BO, units(500))).as("never more than is owed").isEqualTo(units(110));
            assertThat(Debts.inDebt(BO)).isFalse();
        } finally {
            Debts.retract(fines);
        }
    }

    @Test
    @DisplayName("with debt switched off a fine somebody cannot pay is refused and nothing is taken or recorded")
    void refusedWithoutDebt() {
        balances.put(BO, units(100));
        FineService fines = service(ModerationSettings.DEFAULTS.withUnpaidFinesBecomeDebt(false));

        FineService.Result result = fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "griefing", null, FineService.Kind.FINE);

        assertThat(result.status()).isEqualTo(FineService.Status.CANNOT_PAY);
        assertThat(balances.get(BO)).isEqualTo(units(100));
        assertThat(recorded).isEmpty();
        assertThat(ledger.of(BO)).isEmpty();
    }

    @Test
    @DisplayName("without an economy a fine is refused, never free")
    void noEconomyNoFine() {
        Economies.retract(bank);
        FineService fines = service(ModerationSettings.DEFAULTS);

        assertThat(fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "x", null, FineService.Kind.FINE).status())
                .isEqualTo(FineService.Status.NO_ECONOMY);
        assertThat(recorded).isEmpty();
        Economies.provide(null, bank);
    }

    @Test
    @DisplayName("a fine for a player who has never had an account works: the account is made")
    void offlineWithoutAccount() {
        FineService fines = service(ModerationSettings.DEFAULTS);

        FineService.Result result = fines.fine(ADMIN, "Admin", BO, "Bo", units(10), "x", null, FineService.Kind.FINE);

        assertThat(result.done()).isTrue();
        assertThat(fines.owed(BO)).isEqualTo(units(10));
    }

    @Test
    @DisplayName("a moderator may not fine past the limit, an admin may, and zero is no limit")
    void moderatorLimit() {
        balances.put(BO, units(10_000));
        FineService fines = service(ModerationSettings.DEFAULTS.withModFineMax("500"));

        FineService.Result tooMuch = fines.fine(MOD, "Mod", BO, "Bo", units(501), "x", null, FineService.Kind.FINE);
        assertThat(tooMuch.status()).isEqualTo(FineService.Status.TOO_MUCH);
        assertThat(balances.get(BO)).isEqualTo(units(10_000));
        assertThat(fines.fine(MOD, "Mod", BO, "Bo", units(500), "x", null, FineService.Kind.FINE).done()).isTrue();
        assertThat(fines.fine(ADMIN, "Admin", BO, "Bo", units(5_000), "x", null, FineService.Kind.FINE).done()).isTrue();
        assertThat(fines.limitFor(MOD)).isPresent();
        assertThat(fines.limitFor(ADMIN)).isEmpty();

        FineService unlimited = service(ModerationSettings.DEFAULTS);
        assertThat(unlimited.fine(MOD, "Mod", BO, "Bo", units(1_000), "x", null, FineService.Kind.FINE).done()).isTrue();
    }

    @Test
    @DisplayName("the victim gets their share from the fined player, and the rest is the server's")
    void victimShare() {
        balances.put(BO, units(1_000));
        FineService fines = service(ModerationSettings.DEFAULTS.withVictimSharePercent(40));

        FineService.Result result = fines.fine(ADMIN, "Admin", BO, "Bo", units(500), "griefing", VICTIM, FineService.Kind.FINE);

        assertThat(balances.get(VICTIM)).isEqualTo(units(200));
        assertThat(balances.get(BO)).isEqualTo(units(500));
        assertThat(result.charge().toVictim()).isEqualTo(units(200));
        assertThat(result.charge().paid()).isEqualTo(units(300));
    }

    @Test
    @DisplayName("with the share off, naming a victim changes nothing")
    void victimShareOff() {
        balances.put(BO, units(1_000));
        FineService fines = service(ModerationSettings.DEFAULTS);

        fines.fine(ADMIN, "Admin", BO, "Bo", units(500), "x", VICTIM, FineService.Kind.FINE);

        assertThat(balances.getOrDefault(VICTIM, Money.ZERO)).isEqualTo(Money.ZERO);
        assertThat(balances.get(BO)).isEqualTo(units(500));
    }

    @Test
    @DisplayName("revoking gives back what the server took, drops the debt, and can only happen once")
    void revoke() {
        balances.put(BO, units(100));
        FineService fines = service(ModerationSettings.DEFAULTS);
        FineRecord record = fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "x", null, FineService.Kind.FINE).record();

        assertThat(fines.revoke(ADMIN, "Admin", record.id(), "Bo")).isEqualTo(FineService.RevokeStatus.DONE);
        assertThat(balances.get(BO)).isEqualTo(units(100));
        assertThat(fines.owed(BO)).isEqualTo(Money.ZERO);
        assertThat(fines.revoke(ADMIN, "Admin", record.id(), "Bo")).isEqualTo(FineService.RevokeStatus.ALREADY);
        assertThat(balances.get(BO)).as("no second refund").isEqualTo(units(100));
        assertThat(told).contains("moderation.fine.revoked-for-you");
    }

    @Test
    @DisplayName("forgiving writes the debt off without paying anything back")
    void forgive() {
        balances.put(BO, units(100));
        FineService fines = service(ModerationSettings.DEFAULTS);
        fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "x", null, FineService.Kind.FINE);

        assertThat(fines.forgive(ADMIN, "Admin", BO, "Bo")).isEqualTo(units(150));
        assertThat(fines.owed(BO)).isEqualTo(Money.ZERO);
        assertThat(balances.get(BO)).isEqualTo(Money.ZERO);
        assertThat(fines.forgive(ADMIN, "Admin", BO, "Bo")).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a player may pay part of their debt or all of it")
    void paying() {
        balances.put(BO, units(100));
        FineService fines = service(ModerationSettings.DEFAULTS);
        fines.fine(ADMIN, "Admin", BO, "Bo", units(250), "x", null, FineService.Kind.FINE);
        balances.put(BO, units(1_000));

        FineService.Payment part = fines.pay(BO, units(50));
        assertThat(part.status()).isEqualTo(FineService.PayStatus.DONE);
        assertThat(part.paid()).isEqualTo(units(50));
        assertThat(part.remaining()).isEqualTo(units(100));
        assertThat(balances.get(BO)).isEqualTo(units(950));

        FineService.Payment rest = fines.pay(BO, null);
        assertThat(rest.remaining()).isEqualTo(Money.ZERO);
        assertThat(fines.pay(BO, null).status()).isEqualTo(FineService.PayStatus.NOTHING_OWED);
        assertThat(balances.get(BO)).isEqualTo(units(850));
    }

    @Test
    @DisplayName("somebody who cannot afford to pay is told so and nothing moves")
    void payingWithoutMoney() {
        balances.put(BO, units(10));
        FineService fines = service(ModerationSettings.DEFAULTS);
        fines.fine(ADMIN, "Admin", BO, "Bo", units(50), "x", null, FineService.Kind.FINE);

        assertThat(fines.pay(BO, units(5)).status()).isEqualTo(FineService.PayStatus.NOT_ENOUGH);
        assertThat(fines.owed(BO)).isEqualTo(units(40));
    }

    @Test
    @DisplayName("a warning costs the next rung of the ladder, charged and tied to the warning")
    void warningFine() {
        balances.put(BO, units(1_000));
        FineService fines = service(ModerationSettings.DEFAULTS.withWarnFine("50, 100"));

        FineService.Warned first = fines.chargeForWarning(BO, 1).orElseThrow();
        Punishment warning = new Punishment(null, BO, PunishmentKind.WARNING, null, "spam", Instant.now(), null, null, null, null);
        fines.attach(first, warning, "Bo");
        assertThat(balances.get(BO)).isEqualTo(units(950));
        assertThat(fines.finesOn(warning.id())).hasSize(1);

        fines.chargeForWarning(BO, 2).orElseThrow();
        fines.chargeForWarning(BO, 7).orElseThrow();
        assertThat(balances.get(BO)).isEqualTo(units(750));
    }

    @Test
    @DisplayName("with warnings free, nothing is charged and no economy is needed")
    void warningsFreeByDefault() {
        Economies.retract(bank);
        FineService fines = service(ModerationSettings.DEFAULTS);

        assertThat(fines.chargeForWarning(BO, 1)).isEmpty();
        Economies.provide(null, bank);
    }

    @Test
    @DisplayName("a warning fine that cannot be charged for lack of an economy is skipped, not the warning")
    void warningFineWithoutEconomy() {
        Economies.retract(bank);
        FineService fines = service(ModerationSettings.DEFAULTS.withWarnFine("50"));

        assertThat(fines.chargeForWarning(BO, 1)).isEmpty();
        Economies.provide(null, bank);
    }
}
