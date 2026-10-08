package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.economy.model.SellPricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every default spelled out, and the reading of amounts and fractions. */
public class EconomySettingsTest {

    /** The defaults with some keys changed, through the same path a typed /settings change takes. */
    public static EconomySettings with(String... keyValues) {
        SettingsStore<EconomySettings> store = new SettingsStore<>(
                SettingsSchema.of(EconomySettings.class, EconomySettings.DEFAULTS),
                Path.of("target", "no-such-economy-settings.yml"));
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!store.set(keyValues[i], keyValues[i + 1])) {
                throw new AssertionError("refused: " + keyValues[i] + " = " + keyValues[i + 1]);
            }
        }
        return store.current();
    }

    @Test
    @DisplayName("the schema accepts the record, so the file and /settings can be built from it")
    void schema() {
        assertThat(SettingsSchema.of(EconomySettings.class, EconomySettings.DEFAULTS).settings()).hasSizeGreaterThan(70);
    }

    @Test
    @DisplayName("the defaults are what the comments promise")
    void defaults() {
        EconomySettings d = EconomySettings.DEFAULTS;
        Currency currency = d.currency();
        assertThat(currency.singular()).isEqualTo("Coin");
        assertThat(currency.plural()).isEqualTo("Coins");
        assertThat(currency.symbol()).isEqualTo("⛃");
        assertThat(currency.decimals()).isEqualTo(2);
        assertThat(currency.placement()).isEqualTo(Currency.Placement.BEFORE);
        assertThat(currency.nameStyle().isGradient()).isTrue();
        assertThat(d.starting()).isEqualTo(Money.of(10_000));
        assertThat(d.most()).isEqualTo(Money.of(100_000_000_000_000L));
        assertThat(d.balanceOnActionBar()).isTrue();
        assertThat(d.historyDays()).isEqualTo(90);

        assertThat(d.payEnabled()).isTrue();
        assertThat(d.payMinimumMoney()).isEqualTo(Money.of(1));
        assertThat(d.payTax()).isZero();
        assertThat(d.payConfirmAboveMoney()).isEqualTo(Money.of(100_000));
        assertThat(d.payCooldownSeconds()).isEqualTo(2);
        assertThat(d.payOffline()).isTrue();
        assertThat(d.billsEnabled()).isTrue();
        assertThat(d.billMinutes()).isEqualTo(5);

        assertThat(d.cashEnabled()).isTrue();
        assertThat(d.denominations()).hasSize(6);
        assertThat(d.chequesEnabled()).isTrue();
        assertThat(d.serialNotes()).isTrue();
        assertThat(d.depositOnRightClick()).isTrue();
        assertThat(d.withdrawFee()).isZero();
        assertThat(d.mostPieces()).isEqualTo(576);

        assertThat(d.shopEnabled()).isTrue();
        assertThat(d.sellingEnabled()).isTrue();
        assertThat(d.buyMarkupClamped()).isEqualTo(1.0);
        assertThat(d.sellRatioClamped()).isEqualTo(0.4);
        assertThat(d.sellPricing()).isEqualTo(SellPricing.AUTOMATIC);
        assertThat(d.customValues()).isEmpty();
        assertThat(d.sellPrices()).isEmpty();
        assertThat(d.buyPrices()).isEmpty();
        assertThat(d.notSold()).isEmpty();
        assertThat(d.notBought()).isEmpty();
        assertThat(d.deriveFromRecipes()).isTrue();
        assertThat(d.craftMarkupClamped()).isEqualTo(0.1);
        assertThat(d.smeltMarkupClamped()).isEqualTo(0.15);
        assertThat(d.dynamicPrices()).isTrue();
        assertThat(d.priceSwingClamped()).isEqualTo(0.5);
        assertThat(d.pressurePerStackClamped()).isEqualTo(0.02);
        assertThat(d.recoveryHoursClamped()).isEqualTo(12.0);
        for (Category category : Category.values()) {
            assertThat(d.categoryOpen(category)).as(category.title()).isTrue();
        }

        assertThat(d.mobRewardsEnabled()).isTrue();
        assertThat(d.mobRewards()).contains("zombie 2", "warden 250");
        assertThat(d.spawnerMobsPay()).isFalse();
        assertThat(d.miningRewardsEnabled()).isTrue();
        assertThat(d.miningRewards()).contains("diamond_ore 10");
        assertThat(d.salaryEnabled()).isTrue();
        assertThat(d.salaryMoney()).isEqualTo(Money.of(1_000));
        assertThat(d.salaryMinutes()).isEqualTo(30);
        assertThat(d.afkMinutes()).isEqualTo(5);
        assertThat(d.dailyEnabled()).isTrue();
        assertThat(d.dailyMoney()).isEqualTo(Money.of(5_000));
        assertThat(d.dailyBonusMoney()).isEqualTo(Money.of(1_000));
        assertThat(d.dailyStreakMost()).isEqualTo(7);
        assertThat(d.advancementRewardsEnabled()).isTrue();
        assertThat(d.advancementMoney()).isEqualTo(Money.of(2_500));
        assertThat(d.hourlyCapMoney()).isEqualTo(Money.of(200_000));

        assertThat(d.interestEnabled()).isTrue();
        assertThat(d.interestRate()).isEqualTo(0.0025);
        assertThat(d.interestMinutes()).isEqualTo(60);
        assertThat(d.interestCapMoney()).isEqualTo(Money.of(2_500));
        assertThat(d.baltopEnabled()).isTrue();
    }

    @Test
    @DisplayName("an unreadable amount falls back to its default, never to free")
    void unreadable() {
        assertThat(with("accounts.starting-balance", "lots").starting()).isEqualTo(Money.of(10_000));
        assertThat(with("pay.minimum", "five").payMinimumMoney()).isEqualTo(Money.of(1));
    }

    @Test
    @DisplayName("selling is always kept below buying, whatever the ratio says")
    void sellBelowBuy() {
        assertThat(with("shop.sell-ratio", "3").sellRatioClamped()).isEqualTo(0.95);
        assertThat(with("pay.tax-percent", "90").payTax()).isEqualTo(0.5);
        assertThat(with("shop.price-swing", "5").priceSwingClamped()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("lists are typed comma separated")
    void lists() {
        assertThat(with("shop.sell-prices", "diamond 40, iron_ingot 3").sellPrices())
                .isEqualTo(List.of("diamond 40", "iron_ingot 3"));
    }

    @Test
    @DisplayName("every shop category has a key of its own")
    void categoryKeys() {
        for (Category category : Category.values()) {
            assertThat(with(EconomySettings.categoryKey(category), "false").categoryOpen(category)).isFalse();
        }
    }
}
