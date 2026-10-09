package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.economy.model.SellPricing;
import org.bukkit.Material;
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
        assertThat(currency.decimals()).as("whole coins only").isZero();
        assertThat(currency.placement()).isEqualTo(Currency.Placement.BEFORE);
        assertThat(currency.nameStyle().isGradient()).isTrue();
        assertThat(d.starting()).isEqualTo(Money.of(1_000));
        assertThat(d.most()).isEqualTo(Money.of(1_000_000_000_000L));
        assertThat(d.balanceOnActionBar()).isTrue();
        assertThat(d.historyDays()).isEqualTo(90);

        assertThat(d.payEnabled()).isTrue();
        assertThat(d.payMinimumMoney()).isEqualTo(Money.of(1));
        assertThat(d.payTax()).isZero();
        assertThat(d.payConfirmAboveMoney()).isEqualTo(Money.of(10_000));
        assertThat(d.payCooldownSeconds()).isEqualTo(2);
        assertThat(d.payOffline()).isTrue();
        assertThat(d.billsEnabled()).isTrue();
        assertThat(d.billMinutes()).isEqualTo(5);
        assertThat(d.hireEnabled()).isTrue();
        assertThat(d.hireLeastMinutes()).isEqualTo(10);
        assertThat(d.hireMostContracts()).isEqualTo(10);
        assertThat(d.hireMostMissed()).isEqualTo(3);

        assertThat(d.cashEnabled()).isTrue();
        assertThat(d.coinItem()).isEqualTo(Material.GOLD_NUGGET);
        assertThat(d.coinModel()).isEmpty();
        assertThat(d.chequesEnabled()).isTrue();
        assertThat(d.depositOnRightClick()).isTrue();
        assertThat(d.withdrawFee()).isZero();
        assertThat(d.mostPieces()).isEqualTo(2304);

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
        assertThat(d.lowestMultiplier()).as("selling can halve a price").isEqualTo(0.5);
        assertThat(d.highestMultiplier()).as("buying can double it").isEqualTo(2.0);
        assertThat(d.pressurePerStackClamped()).isEqualTo(0.02);
        assertThat(d.recoveryHoursClamped()).isEqualTo(12.0);
        assertThat(d.enchantedSelling()).isTrue();
        assertThat(d.enchantBooks()).isTrue();
        assertThat(d.enchantPriceMoney()).isEqualTo(Money.of(500));
        assertThat(d.enchantTreasure()).as("Mending and the like stay loot unless an owner sells them").isFalse();
        assertThat(d.enchantClosed()).isEmpty();
        assertThat(d.enchantValueMoney()).isEqualTo(Money.of(40));
        for (Category category : Category.values()) {
            assertThat(d.categoryOpen(category)).as(category.title()).isTrue();
        }

        assertThat(d.incomeEnabled()).as("passive income is off until an owner wants it").isFalse();
        assertThat(d.incomeMoney()).isEqualTo(Money.of(10));
        assertThat(d.incomeAwayMoney()).isEqualTo(Money.of(2));
        assertThat(d.incomeMinutes()).isEqualTo(30);
        assertThat(d.afkMinutes()).isEqualTo(5);
        assertThat(d.dailyEnabled()).isTrue();
        assertThat(d.dailyMoney()).isEqualTo(Money.of(500));
        assertThat(d.dailyBonusMoney()).isEqualTo(Money.of(100));
        assertThat(d.dailyStreakMost()).isEqualTo(7);
        assertThat(d.advancementRewardsEnabled()).isTrue();
        assertThat(d.advancementMoney()).isEqualTo(Money.of(250));
        assertThat(d.hourlyCapMoney()).isEqualTo(Money.of(20_000));

        assertThat(d.interestEnabled()).isTrue();
        assertThat(d.interestRate()).isEqualTo(0.0025);
        assertThat(d.interestMinutes()).isEqualTo(60);
        assertThat(d.interestCapMoney()).isEqualTo(Money.of(250));

        assertThat(d.gamblingEnabled()).isTrue();
        assertThat(d.rouletteEnabled()).isTrue();
        assertThat(d.minBetMoney()).isEqualTo(Money.of(1));
        assertThat(d.gambleCooldownSeconds()).as("no wait between games").isZero();
        assertThat(d.mostTickets()).as("no cap on lottery tickets").isZero();
        assertThat(d.houseEdge()).isEqualTo(0.03);
        assertThat(d.dailyLossLimitMoney()).isEqualTo(Money.ZERO);
        assertThat(d.ticketPriceMoney()).isEqualTo(Money.of(100));
        assertThat(d.auctionsEnabled()).isTrue();
        assertThat(d.auctionDefaultSeconds()).isEqualTo(120);
        assertThat(d.auctionMinSeconds()).isEqualTo(60);
        assertThat(d.auctionMaxSeconds()).isEqualTo(600);
        assertThat(d.auctionSmallestStartMoney()).isEqualTo(Money.of(10));
        assertThat(d.auctionStepMoney()).isEqualTo(Money.of(10));
        assertThat(d.auctionStepPercent()).isEqualTo(5.0);
        assertThat(d.auctionSnipeSeconds()).isEqualTo(20);
        assertThat(d.auctionGapSeconds()).isEqualTo(15);
        assertThat(d.auctionListingFeeMoney()).as("so the queue is not filled with junk").isEqualTo(Money.of(1_000));
        assertThat(d.auctionFeePercent()).isEqualTo(5.0);
        assertThat(d.auctionQueueSize()).isEqualTo(10);
        assertThat(d.auctionsPerPlayer()).isEqualTo(2);
        assertThat(d.auctionAnnounceBids()).isTrue();
        assertThat(d.auctionBossBar()).isTrue();

        assertThat(d.xpTradeEnabled()).isTrue();
        assertThat(d.xpBuyMoney()).isEqualTo(Money.of(3));
        assertThat(d.xpSellMoney()).isEqualTo(Money.of(1));
        assertThat(with("xp.sell-per-point", "10").xpSellMoney()).as("never above the buying price")
                .isEqualTo(Money.of(3));
        assertThat(d.wealthTaxEnabled()).as("nobody's money is taxed unless an owner wants it").isFalse();
        assertThat(d.wealthTaxPercent()).isEqualTo(1.0);
        assertThat(d.wealthTaxHours()).isEqualTo(24);
        assertThat(d.wealthTaxAllowanceMoney()).isEqualTo(Money.ZERO);
        assertThat(d.rafflesEnabled()).isTrue();
        assertThat(d.giveawaysEnabled()).isTrue();
        assertThat(d.raffleListingFeeMoney()).as("starting a raffle is free").isEqualTo(Money.ZERO);
        assertThat(d.raffleFeePercent()).isEqualTo(5.0);
        assertThat(d.raffleDefaultMinutes()).isEqualTo(30);
        assertThat(d.raffleMinMinutes()).isEqualTo(5);
        assertThat(d.raffleMaxMinutes()).isEqualTo(1440);
        assertThat(d.raffleMostRunning()).isEqualTo(5);
        assertThat(d.rafflesPerHost()).isEqualTo(1);
        assertThat(d.raffleSmallestTicketMoney()).isEqualTo(Money.of(1));

        assertThat(d.sidebarEnabled()).as("the balance is in the sidebar from the start").isTrue();
        assertThat(d.baltopEnabled()).isTrue();
    }

    @Test
    @DisplayName("an amount with decimals is not money here: there are only whole coins")
    void wholeCoins() {
        assertThat(EconomySettings.DEFAULTS.currency().parse("2.5")).isEmpty();
        assertThat(EconomySettings.DEFAULTS.currency().parse("1.5k")).contains(Money.of(1_500));
        assertThat(EconomySettings.DEFAULTS.currency().format(Money.of(1_234_567))).isEqualTo("⛃1,234,567");
    }

    @Test
    @DisplayName("an unreadable amount falls back to its default, never to free")
    void unreadable() {
        assertThat(with("accounts.starting-balance", "lots").starting()).isEqualTo(Money.of(1_000));
        assertThat(with("pay.minimum", "five").payMinimumMoney()).isEqualTo(Money.of(1));
    }

    @Test
    @DisplayName("selling is always kept below buying, whatever the ratio says")
    void sellBelowBuy() {
        assertThat(with("shop.sell-ratio", "3").sellRatioClamped()).isEqualTo(0.95);
        assertThat(with("pay.tax-percent", "90").payTax()).isEqualTo(0.5);
        assertThat(with("shop.lowest-multiplier", "-1").lowestMultiplier()).isEqualTo(0.05);
        assertThat(with("shop.highest-multiplier", "50").highestMultiplier()).isEqualTo(10.0);
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

    @Test
    @DisplayName("a game with nothing of its own set plays by the casino's bets and edge")
    void gamesFollowTheCasino() {
        EconomySettings d = EconomySettings.DEFAULTS;
        for (de.raindancer.modules.economy.model.Game game : de.raindancer.modules.economy.model.Game.values()) {
            assertThat(d.minBet(game)).as(game.key()).isEqualTo(d.minBetMoney());
            assertThat(d.edge(game)).as(game.key()).isEqualTo(d.houseEdge());
            assertThat(d.gameOn(game)).as(game.key()).isTrue();
        }
        assertThat(d.naturalPays()).isEqualTo(de.raindancer.modules.economy.model.NaturalPay.THREE_TO_TWO);
        assertThat(d.dealerHitsSoft17()).isFalse();
        assertThat(d.baccaratTiePays()).isEqualTo(8);
        assertThat(d.baccaratCommission()).isEqualTo(0.05);
        assertThat(d.crashMost()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a game's own bets and edge win over the casino's; a typo falls back rather than to zero")
    void gamesOwnSettings() {
        var slots = de.raindancer.modules.economy.model.Game.SLOTS;
        var dice = de.raindancer.modules.economy.model.Game.DICE;
        EconomySettings s = with("slots.min-bet", "5",
                "slots.house-edge-percent", "8", "dice.house-edge-percent", "nonsense",
                "roulette.house-edge-percent", "90");
        assertThat(s.minBet(slots)).isEqualTo(Money.of(5));
        assertThat(s.edge(slots)).isEqualTo(0.08);
        assertThat(s.edge(dice)).as("unreadable: the casino's").isEqualTo(s.houseEdge());
        assertThat(s.edge(de.raindancer.modules.economy.model.Game.ROULETTE)).as("at most 50 %").isEqualTo(0.5);
        assertThat(with("features.gambling", "false").gameOn(slots)).as("gambling off closes every game").isFalse();
        assertThat(with("features.slots", "false").gameOn(slots)).isFalse();
    }

    @Test
    @DisplayName("a largest bet left in an old settings file caps nothing: there is no largest bet any more")
    void noLargestBet() {
        assertThat(java.util.Arrays.stream(EconomySettings.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("maxbet"));
        SettingsStore<EconomySettings> store = new SettingsStore<>(
                SettingsSchema.of(EconomySettings.class, EconomySettings.DEFAULTS),
                Path.of("target", "no-such-economy-settings.yml"));
        assertThat(store.set("gamble.max-bet", "200")).as("not a setting any more").isFalse();
        assertThat(store.set("crash.max-bet", "200")).isFalse();
    }

    @Test
    @DisplayName("spawn eggs are for sale by default, the bosses' eggs not")
    void spawnEggs() {
        EconomySettings d = EconomySettings.DEFAULTS;
        assertThat(d.spawnEggs()).isTrue();
        assertThat(d.spawnEggValueMoney()).isEqualTo(Money.of(2_000));
        assertThat(d.spawnEggsClosed()).contains("ender_dragon", "wither");
    }

    @Test
    @DisplayName("loans: 100 to 10,000, 10 % once, due in a week, 2 % a day late; percentages kept in bounds")
    void loans() {
        EconomySettings d = EconomySettings.DEFAULTS;
        assertThat(d.loansEnabled()).isTrue();
        assertThat(d.loanLeastMoney()).isEqualTo(Money.of(100));
        assertThat(d.loanMostMoney()).isEqualTo(Money.of(10_000));
        assertThat(d.loanInterest()).isEqualTo(0.10);
        assertThat(d.loanDays()).isEqualTo(7);
        assertThat(d.loanLate()).isEqualTo(0.02);
        assertThat(d.overdueStopsGambling()).isTrue();
        assertThat(with("loans.interest-percent", "500").loanInterest()).isEqualTo(1.0);
        assertThat(with("loans.late-percent", "-3").loanLate()).isZero();
    }
}
