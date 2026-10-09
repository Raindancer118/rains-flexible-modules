package de.raindancer.modules.chat.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.chat.ChatSettings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdServiceTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final UUID player = UUID.randomUUID();
    private final Bank bank = new Bank(Money.of(10_000));
    private final FreezeService freeze = new FreezeService();

    @AfterEach
    void reset() {
        Economies.clear();
    }

    private ChatSettings settings(boolean on, String price) {
        return new ChatSettings("<name>: <message>", true, true, NamedTextColor.WHITE, NamedTextColor.WHITE, false,
                true, true, 70, 8, true, 0, 0, true, 200, true, true, 120,
                on, price, 600, 120, ChatSettings.AD_FORMAT);
    }

    private AdService service(ChatSettings settings) {
        return new AdService(new ChatQualityService(settings, now::get), freeze, settings, now::get);
    }

    @Test
    @DisplayName("with default settings ads are off and nothing is charged")
    void offByDefault() {
        Economies.provide(mock(Plugin.class), bank);
        AdService ads = service(ChatSettings.DEFAULTS);

        assertThat(ads.place(player, "hello", false, false).verdict().reason()).isEqualTo("chat.ad.off");
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("a free ad needs no economy at all")
    void freeNeedsNoEconomy() {
        AdService ads = service(settings(true, "0"));

        assertThat(ads.place(player, "hello", false, false).placed()).isTrue();
    }

    @Test
    @DisplayName("a priced ad is charged with its source before it is placed")
    void charged() {
        Economies.provide(mock(Plugin.class), bank);
        AdService ads = service(settings(true, "25"));

        AdService.Result result = ads.place(player, "hello", false, false);

        assertThat(result.placed()).isTrue();
        assertThat(bank.calls).containsExactly("withdraw 2500 chat.ad");
        assertThat(result.charged()).isEqualTo(Money.of(2500));
    }

    @Test
    @DisplayName("a priced ad without an economy is refused, never free")
    void noEconomy() {
        AdService ads = service(settings(true, "25"));

        assertThat(ads.place(player, "hello", false, false).verdict().reason()).isEqualTo("chat.ad.no-economy");
    }

    @Test
    @DisplayName("somebody who cannot afford it is told the price and nothing is placed")
    void cannotAfford() {
        Economies.provide(mock(Plugin.class), new Bank(Money.of(100)));
        AdService ads = service(settings(true, "25"));

        AdService.Result result = ads.place(player, "hello", false, false);

        assertThat(result.placed()).isFalse();
        assertThat(result.verdict().reason()).isEqualTo("chat.ad.cannot-afford");
    }

    @Test
    @DisplayName("an ad the chat filters refuse is not charged")
    void filteredIsNotCharged() {
        Economies.provide(mock(Plugin.class), bank);
        AdService ads = service(settings(true, "25"));

        AdService.Result result = ads.place(player, "BUY MY COBBLESTONE RIGHT NOW", false, false);

        assertThat(result.verdict().reason()).isEqualTo("chat.quality.caps");
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("frozen chat refuses an ad, and nothing is charged")
    void frozen() {
        Economies.provide(mock(Plugin.class), bank);
        freeze.freeze();
        AdService ads = service(settings(true, "25"));

        assertThat(ads.place(player, "hello", false, false).verdict().reason()).isEqualTo("chat.frozen");
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("the next ad waits for the cooldown, and staff who bypass the filters do not")
    void cooldown() {
        AdService ads = service(settings(true, "0"));
        assertThat(ads.place(player, "first", false, false).placed()).isTrue();

        now.addAndGet(10_000);
        AdService.Result second = ads.place(player, "second one", false, false);
        assertThat(second.verdict().reason()).isEqualTo("chat.ad.cooldown");
        assertThat(second.verdict().detail()).isEqualTo("590");
        assertThat(ads.place(player, "second one", true, false).placed()).isTrue();
    }

    @Test
    @DisplayName("forgetting a player drops their cooldown")
    void forgets() {
        AdService ads = service(settings(true, "0"));
        ads.place(player, "first", false, false);
        ads.forget(player);

        assertThat(ads.place(player, "again", false, false).placed()).isTrue();
    }

    private static final class Bank implements Economy {
        final List<String> calls = new ArrayList<>();
        final Money money;

        Bank(Money money) {
            this.money = money;
        }

        @Override
        public String name() {
            return "test";
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
            return money;
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            if (!money.isAtLeast(amount)) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, money);
            }
            calls.add("withdraw " + amount.minor() + " " + source);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }
    }
}
