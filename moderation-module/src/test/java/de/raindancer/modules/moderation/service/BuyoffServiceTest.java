package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.rules.BuyoffRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Buying off a mute: the quote, refusals, the charge, and the refund when the mute ended meanwhile. */
class BuyoffServiceTest {

    private static final UUID BO = UUID.randomUUID();

    private final Map<UUID, Money> balances = new HashMap<>();
    private final AtomicInteger lifted = new AtomicInteger();
    private Optional<Punishment> mute = Optional.empty();
    private boolean liftWorks = true;
    private Economy bank;

    private static Money units(long whole) {
        return Currency.DEFAULT.ofMajor(whole);
    }

    private static Punishment muteOf(Duration given, Duration left) {
        Instant now = Instant.now();
        return new Punishment(null, BO, PunishmentKind.MUTE, null, "spam", now.plus(left).minus(given), now.plus(left),
                null, null, null);
    }

    private BuyoffService service(ModerationSettings settings) {
        return new BuyoffService(who -> mute, (who, name, why) -> {
            lifted.incrementAndGet();
            return liftWorks;
        }, settings);
    }

    @BeforeEach
    void economy() {
        bank = new Economy() {
            @Override public String name() { return "test"; }
            @Override public Currency currency() { return Currency.DEFAULT; }
            @Override public boolean hasAccount(UUID player) { return true; }
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
                return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, balance(from));
            }
        };
        Economies.provide(null, bank);
    }

    @AfterEach
    void noEconomy() {
        Economies.retract(bank);
    }

    @Test
    @DisplayName("by default nothing can be bought off")
    void offByDefault() {
        mute = Optional.of(muteOf(Duration.ofHours(2), Duration.ofHours(1)));

        assertThat(service(ModerationSettings.DEFAULTS).mayBuyOff(BO).reason()).isEqualTo(BuyoffRule.OFF);
    }

    @Test
    @DisplayName("the quote is the price per started hour left")
    void quote() {
        mute = Optional.of(muteOf(Duration.ofHours(5), Duration.ofMinutes(150)));
        BuyoffService service = service(ModerationSettings.DEFAULTS.withMuteBuyoffPerHour("20"));

        BuyoffService.Quote quote = service.quote(BO).orElseThrow();

        assertThat(quote.hours()).isEqualTo(3);
        assertThat(quote.price()).isEqualTo(units(60));
    }

    @Test
    @DisplayName("a permanent mute and a mute longer than the limit are never for sale")
    void neverForSale() {
        BuyoffService service = service(ModerationSettings.DEFAULTS.withMuteBuyoffPerHour("20")
                .withMuteBuyoffLongestHours(24));

        mute = Optional.of(new Punishment(null, BO, PunishmentKind.MUTE, null, "x", Instant.now(), null, null, null, null));
        assertThat(service.mayBuyOff(BO).reason()).isEqualTo(BuyoffRule.PERMANENT);
        mute = Optional.of(muteOf(Duration.ofHours(48), Duration.ofHours(40)));
        assertThat(service.mayBuyOff(BO).reason()).isEqualTo(BuyoffRule.TOO_LONG);
        mute = Optional.empty();
        assertThat(service.mayBuyOff(BO).reason()).isEqualTo(BuyoffRule.NOT_MUTED);
    }

    @Test
    @DisplayName("paying charges the price and lifts the mute")
    void buyOff() {
        mute = Optional.of(muteOf(Duration.ofHours(3), Duration.ofHours(2)));
        balances.put(BO, units(100));

        BuyoffService.Outcome outcome = service(ModerationSettings.DEFAULTS.withMuteBuyoffPerHour("20")).buyOff(BO, "Bo");

        assertThat(outcome.status()).isEqualTo(BuyoffService.Status.DONE);
        assertThat(balances.get(BO)).isEqualTo(units(60));
        assertThat(lifted).hasValue(1);
    }

    @Test
    @DisplayName("somebody who cannot pay stays muted and is charged nothing")
    void cannotPay() {
        mute = Optional.of(muteOf(Duration.ofHours(3), Duration.ofHours(2)));
        balances.put(BO, units(10));

        BuyoffService.Outcome outcome = service(ModerationSettings.DEFAULTS.withMuteBuyoffPerHour("20")).buyOff(BO, "Bo");

        assertThat(outcome.status()).isEqualTo(BuyoffService.Status.CANNOT_PAY);
        assertThat(balances.get(BO)).isEqualTo(units(10));
        assertThat(lifted).hasValue(0);
    }

    @Test
    @DisplayName("a mute that ended while paying is refunded")
    void refundedWhenAlreadyOver() {
        mute = Optional.of(muteOf(Duration.ofHours(3), Duration.ofHours(2)));
        balances.put(BO, units(100));
        liftWorks = false;

        BuyoffService.Outcome outcome = service(ModerationSettings.DEFAULTS.withMuteBuyoffPerHour("20")).buyOff(BO, "Bo");

        assertThat(outcome.status()).isEqualTo(BuyoffService.Status.FAILED);
        assertThat(balances.get(BO)).isEqualTo(units(100));
    }
}
