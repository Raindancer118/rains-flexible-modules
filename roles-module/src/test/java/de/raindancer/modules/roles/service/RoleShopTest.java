package de.raindancer.modules.roles.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.roles.model.Choice;
import de.raindancer.modules.roles.model.Ownership;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.store.ChoiceBook;
import de.raindancer.modules.roles.store.OwnedBook;
import de.raindancer.modules.roles.store.RoleCatalogue;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoleShopTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(100 * DAY);
    private final UUID tom = UUID.randomUUID();
    private final java.util.concurrent.atomic.AtomicBoolean selling = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final Bank bank = new Bank(Money.of(1_000_00));
    private OwnedBook owned;
    private ChoiceBook choices;
    private RoleShop shop;
    private Role cook;
    private Role mage;
    private Role farmer;

    @BeforeEach
    void start() {
        Economies.provide(mock(Plugin.class), bank);
        RoleCatalogue catalogue = new RoleCatalogue(new YamlStore(folder.resolve("roles.yml")), () ->
                new ByteArrayInputStream("""
                        roles:
                          cook:
                            price: 200
                          mage:
                            rent-per-month: 30
                          farmer:
                            title: Farmer
                        """.getBytes(StandardCharsets.UTF_8)));
        catalogue.reload();
        owned = new OwnedBook(new YamlStore(folder.resolve("owned.yml")));
        owned.load();
        choices = new ChoiceBook(new YamlStore(folder.resolve("choices.yml")));
        choices.load();
        shop = new RoleShop(catalogue, owned, choices, now::get, id -> false, selling::get);
        cook = catalogue.find("cook").orElseThrow();
        mage = catalogue.find("mage").orElseThrow();
        farmer = catalogue.find("farmer").orElseThrow();
    }

    @AfterEach
    void reset() {
        Economies.clear();
    }

    @Test
    @DisplayName("a role without a price is open and never touches the economy")
    void freeRole() {
        assertThat(shop.may(tom, farmer)).isTrue();
        assertThat(shop.buy(tom, farmer).outcome()).isEqualTo(RoleShop.Outcome.NOT_FOR_SALE);
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("buying charges the price with its source once, and the role is then open for good")
    void buy() {
        assertThat(shop.may(tom, cook)).isFalse();

        RoleShop.Result result = shop.buy(tom, cook);

        assertThat(result.outcome()).isEqualTo(RoleShop.Outcome.DONE);
        assertThat(bank.calls).containsExactly("withdraw 20000 roles.buy");
        assertThat(shop.may(tom, cook)).isTrue();
        assertThat(shop.buy(tom, cook).outcome()).isEqualTo(RoleShop.Outcome.ALREADY_YOURS);
        assertThat(bank.calls).hasSize(1);
    }

    @Test
    @DisplayName("somebody who cannot pay gets nothing and is not charged")
    void cannotAfford() {
        bank.money = Money.of(100);

        assertThat(shop.buy(tom, cook).outcome()).isEqualTo(RoleShop.Outcome.CANNOT_AFFORD);
        assertThat(shop.may(tom, cook)).isFalse();
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("a price without any economy is never waved through")
    void noEconomy() {
        Economies.clear();

        assertThat(shop.buy(tom, cook).outcome()).isEqualTo(RoleShop.Outcome.NO_ECONOMY);
        assertThat(shop.may(tom, cook)).isFalse();
    }

    @Test
    @DisplayName("when the purchase cannot be saved the money is given back")
    void refundWhenNotSaved() throws Exception {
        Files.writeString(folder.resolve("owned.yml"), "owned: [ this: is: not yaml");
        owned.load();

        assertThat(shop.buy(tom, cook).outcome()).isEqualTo(RoleShop.Outcome.NOT_SAVED);
        assertThat(bank.calls).containsExactly("withdraw 20000 roles.buy", "deposit 20000 roles.buy");
    }

    @Test
    @DisplayName("renting charges the first month with the rent source and sets the due date thirty days out")
    void rent() {
        RoleShop.Result result = shop.rent(tom, mage);

        assertThat(result.outcome()).isEqualTo(RoleShop.Outcome.DONE);
        assertThat(bank.calls).containsExactly("withdraw 3000 roles.rent");
        assertThat(owned.of(tom, "mage").orElseThrow().dueAt()).isEqualTo(130 * DAY);
        assertThat(shop.may(tom, mage)).isTrue();
    }

    @Test
    @DisplayName("rent that is paid when due moves the due date on and keeps the role")
    void rentPaid() {
        shop.rent(tom, mage);
        now.set(130 * DAY);

        List<RoleShop.RentEvent> events = shop.collect(tom);

        assertThat(events).singleElement().satisfies(event -> assertThat(event.paid()).isTrue());
        assertThat(bank.calls).containsExactly("withdraw 3000 roles.rent", "withdraw 3000 roles.rent");
        assertThat(owned.of(tom, "mage").orElseThrow().dueAt()).isEqualTo(160 * DAY);
    }

    @Test
    @DisplayName("nothing happens before the due date")
    void notDue() {
        shop.rent(tom, mage);
        now.set(129 * DAY);

        assertThat(shop.collect(tom)).isEmpty();
        assertThat(bank.calls).hasSize(1);
    }

    @Test
    @DisplayName("rent that cannot be paid lapses the rental and takes the role off, nothing else")
    void rentLapses() {
        shop.rent(tom, mage);
        shop.buy(tom, cook);
        choices.put(new Choice(tom, "mage", 1, 1));
        bank.money = Money.of(10);
        now.set(131 * DAY);

        List<RoleShop.RentEvent> events = shop.collect(tom);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.paid()).isFalse();
            assertThat(event.role().id()).isEqualTo("mage");
        });
        assertThat(shop.may(tom, mage)).isFalse();
        assertThat(choices.of(tom)).isEmpty();
        assertThat(shop.may(tom, cook)).as("a bought role is never touched").isTrue();
    }

    @Test
    @DisplayName("a lapsed rental does not remove a different role the player has chosen")
    void lapseKeepsOtherChoice() {
        shop.rent(tom, mage);
        choices.put(new Choice(tom, "farmer", 1, 1));
        bank.money = Money.of(10);
        now.set(131 * DAY);

        shop.collect(tom);

        assertThat(choices.of(tom)).isPresent();
    }

    @Test
    @DisplayName("a bought role is never charged rent")
    void boughtNoRent() {
        shop.buy(tom, cook);
        now.set(500 * DAY);

        assertThat(shop.collect(tom)).isEmpty();
        assertThat(bank.calls).hasSize(1);
    }

    @Test
    @DisplayName("cancelling a rental ends it without a refund and takes the role off")
    void cancel() {
        shop.rent(tom, mage);
        choices.put(new Choice(tom, "mage", 1, 1));

        assertThat(shop.cancel(tom, mage)).isTrue();

        assertThat(shop.may(tom, mage)).isFalse();
        assertThat(choices.of(tom)).isEmpty();
        assertThat(bank.calls).hasSize(1);
    }

    @Test
    @DisplayName("rent due soon is reported for a warning, a bought role never")
    void warnings() {
        shop.rent(tom, mage);
        shop.buy(tom, cook);
        now.set(128 * DAY);

        assertThat(shop.dueSoon(tom)).extracting(Ownership::role).containsExactly("mage");
        now.set(110 * DAY);
        assertThat(shop.dueSoon(tom)).isEmpty();
    }

    @Test
    @DisplayName("switched off (the default): nothing is bought or rented, the economy is never asked, the role stays closed")
    void switchedOff() {
        selling.set(false);

        assertThat(shop.buy(tom, cook).outcome()).isEqualTo(RoleShop.Outcome.SWITCHED_OFF);
        assertThat(shop.rent(tom, mage).outcome()).isEqualTo(RoleShop.Outcome.SWITCHED_OFF);
        assertThat(shop.may(tom, cook)).isFalse();
        assertThat(shop.buy(tom, farmer).outcome()).as("a free role is not for sale either way")
                .isEqualTo(RoleShop.Outcome.NOT_FOR_SALE);
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("switched off: no economy is needed and no rent is collected, a rental is neither charged nor lapsed")
    void switchedOffCollectsNothing() {
        shop.rent(tom, mage);
        selling.set(false);
        Economies.clear();
        now.set(140 * DAY);

        assertThat(shop.collect(tom)).isEmpty();
        assertThat(shop.dueSoon(tom)).isEmpty();
        assertThat(shop.may(tom, mage)).isTrue();
        assertThat(owned.of(tom, "mage")).isPresent();
    }

    private static final class Bank implements Economy {
        final List<String> calls = new ArrayList<>();
        Money money;

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
        public EconomyResult deposit(UUID player, Money amount, String reason, String source) {
            calls.add("deposit " + amount.minor() + " " + source);
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
