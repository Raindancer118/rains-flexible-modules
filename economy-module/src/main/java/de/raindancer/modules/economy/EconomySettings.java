package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.economy.model.SellPricing;
import org.bukkit.Material;

import java.util.List;

/**
 * Everything an owner decides about money. Every feature has its own switch.
 *
 * <p>Amounts are written as text — {@code "100"}, {@code "0.50"}, {@code "1.5k"} — and read through the
 * currency, so they mean the same thing whatever the decimals are set to. An amount that cannot be read
 * falls back to its default rather than to zero: a typo must not make everything free.
 *
 * <p>Changed in game through {@code store.set(key, text)}, so there are no {@code with…} methods.
 */
@Settings(id = "economy", topics = {
        @Topic(path = "economy", title = "Economy", icon = Material.GOLD_INGOT),
        @Topic(path = "economy/currency", title = "Currency", icon = Material.GOLD_NUGGET),
        @Topic(path = "economy/accounts", title = "Accounts", icon = Material.ENDER_CHEST),
        @Topic(path = "economy/pay", title = "Paying and bills", icon = Material.WRITABLE_BOOK),
        @Topic(path = "economy/cash", title = "Coins and notes", icon = Material.PAPER),
        @Topic(path = "economy/shop", title = "Shop", icon = Material.EMERALD),
        @Topic(path = "economy/shop/categories", title = "Shop categories", icon = Material.CHEST),
        @Topic(path = "economy/earn", title = "Earning", icon = Material.DIAMOND_PICKAXE),
        @Topic(path = "economy/interest", title = "Interest", icon = Material.CLOCK),
        @Topic(path = "economy/gambling", title = "Gambling", icon = Material.GOLD_BLOCK),
        @Topic(path = "economy/display", title = "Sidebar and leaderboards", icon = Material.OAK_SIGN),
})
public record EconomySettings(

        // ------------------------------------------------------------------ currency
        @In("economy/currency") @Title("Name, one") @Describe("What one unit is called: 1 Coin.")
        @Key("currency.singular") String currencySingular,

        @In("economy/currency") @Title("Name, many") @Describe("What several are called: 5 Coins.")
        @Key("currency.plural") String currencyPlural,

        @In("economy/currency") @Title("Symbol")
        @Describe("Written beside every amount. Empty writes the name instead.")
        @Key("currency.symbol") String currencySymbol,

        @In("economy/currency") @Title("Symbol goes")
        @Describe("Before the amount ($5), after it (5 €), or the name instead of a symbol (5 Coins).")
        @Key("currency.placement") Currency.Placement currencyPlacement,

        @In("economy/currency") @Title("Thousands separator") @Describe("1,000 or 1.000 — one character. "
                + "Money comes in whole coins only: there are no decimals anywhere.")
        @Key("currency.group-separator") String groupSeparator,

        @In("economy/currency") @Title("Name colours")
        @Describe("Colour stops and decorations, as in /cosmetics: '#ffd700,#ff8c00|bold'. Easier in "
                + "/eco admin, which has the same painter as names.")
        @Key("currency.name-style") String nameStyle,

        @In("economy/currency") @Title("Symbol colours") @Describe("As the name colours.")
        @Key("currency.symbol-style") String symbolStyle,

        @In("economy/currency") @Title("Number colours") @Describe("As the name colours.")
        @Key("currency.amount-style") String amountStyle,

        // ------------------------------------------------------------------ accounts
        @In("economy/accounts") @Title("Starting balance")
        @Describe("What a player who has never been here before starts with.")
        @Key("accounts.starting-balance") String startingBalance,

        @In("economy/accounts") @Title("Most an account holds")
        @Describe("Anything that would take a balance past this is refused, not trimmed.")
        @Key("accounts.most") String mostBalance,

        @In("economy/accounts") @Title("Show changes above the hotbar")
        @Describe("+5 / -5 with the new balance whenever money moves.")
        @Key("accounts.action-bar") boolean balanceOnActionBar,

        @In("economy/accounts") @Title("Keep statements for") @Range(min = 1, max = 3650)
        @Describe("Days. Older lines of every statement are deleted.")
        @Key("accounts.history-days") int historyDays,

        // ------------------------------------------------------------------ pay
        @In("economy/pay") @Title("Paying each other") @Describe("/pay and the Pay button.")
        @Key("features.pay") boolean payEnabled,

        @In("economy/pay") @Title("Least anybody may pay")
        @Key("pay.minimum") String payMinimum,

        @In("economy/pay") @Title("Tax on payments, percent")
        @Describe("Taken from what arrives and destroyed — a money sink. 0 for none, at most 50.")
        @Key("pay.tax-percent") double payTaxPercent,

        @In("economy/pay") @Title("Ask to confirm above")
        @Describe("A payment larger than this needs a second click.")
        @Key("pay.confirm-above") String payConfirmAbove,

        @In("economy/pay") @Title("Wait between payments") @Range(min = 0, max = 3600)
        @Describe("Seconds. Stops a macro spamming a hundred payments a second into the log.")
        @Key("pay.cooldown-seconds") int payCooldownSeconds,

        @In("economy/pay") @Title("Pay offline players")
        @Describe("They see what arrived when they next join.")
        @Key("pay.offline") boolean payOffline,

        @In("economy/pay") @Title("Bills") @Describe("/bill asks somebody to pay you, with buttons to accept.")
        @Key("features.bills") boolean billsEnabled,

        @In("economy/pay") @Title("A bill stays open for") @Range(min = 1, max = 1440)
        @Describe("Minutes.")
        @Key("bills.minutes") int billMinutes,

        @In("economy/pay") @Title("Hiring") @Describe("/hire pays somebody a wage from your account at an interval.")
        @Key("features.hire") boolean hireEnabled,

        @In("economy/pay") @Title("Shortest pay interval") @Range(min = 1, max = 10080)
        @Describe("Minutes. Stops a wage every second filling statements.")
        @Key("hire.least-minutes") int hireLeastMinutes,

        @In("economy/pay") @Title("Most people one player may employ") @Range(min = 1, max = 500)
        @Key("hire.most-contracts") int hireMostContracts,

        @In("economy/pay") @Title("A job ends after this many missed wages") @Range(min = 1, max = 100)
        @Describe("A wage the employer cannot pay is missed; the employee is told each time.")
        @Key("hire.most-missed") int hireMostMissed,

        // ------------------------------------------------------------------ cash
        @In("economy/cash") @Title("Coins and notes")
        @Describe("Withdrawing money as items you can carry, trade, drop and lose — and paying it back in.")
        @Key("features.cash") boolean cashEnabled,

        @In("economy/cash") @Title("The coin")
        @Describe("What one coin is made of — any item. Change it in game with /eco coin and the item in your "
                + "hand. Coins already out keep working: a coin is recognised by its seal, not its look. A coin "
                + "is never usable as its item: no crafting, smelting, trading or bartering.")
        @Key("cash.coin-item") Material coinItem,

        @In("economy/cash") @Title("The coin's model")
        @Describe("A resource pack item model for the coin, like 'myserver:coin'. Empty keeps the item's own look.")
        @Key("cash.coin-model") String coinModel,

        @In("economy/cash") @Title("Cheques") @Describe("One signed paper for any amount: /withdraw <amount> cheque. Numbered, paid in once.")
        @Key("features.cheques") boolean chequesEnabled,

        @In("economy/cash") @Title("Right click pays cash in")
        @Describe("Right clicking a coin or note pays the stack in; sneaking pays in every piece you carry.")
        @Key("cash.right-click") boolean depositOnRightClick,

        @In("economy/cash") @Title("Withdrawal fee, percent") @Describe("0 for none, at most 50.")
        @Key("cash.withdraw-fee-percent") double withdrawFeePercent,

        @In("economy/cash") @Title("Most coins per withdrawal") @Range(min = 1, max = 2304)
        @Describe("Stops /withdraw 1000000 filling the world with coins. Larger sums: a cheque.")
        @Key("cash.most-pieces") int mostPieces,

        // ------------------------------------------------------------------ shop
        @In("economy/shop") @Title("Shop") @Describe("/shop, sorted like the creative inventory.")
        @Key("features.shop") boolean shopEnabled,

        @In("economy/shop") @Title("Selling to the shop") @Describe("/sell and the Sell buttons.")
        @Key("features.selling") boolean sellingEnabled,

        @In("economy/shop") @Title("Buying costs this times the value")
        @Describe("1.0 sells at value; 1.25 adds a quarter.")
        @Key("shop.buy-markup") double buyMarkup,

        @In("economy/shop") @Title("Selling pays this times the value")
        @Describe("Always kept below what buying costs, so nothing can be bought and sold back at a profit.")
        @Key("shop.sell-ratio") double sellRatio,

        @In("economy/shop") @Title("Sell prices are")
        @Describe("Automatic: the sell ratio times each item's value, with the custom sell prices "
                + "below taking precedence. Custom only: only items listed in the custom sell prices "
                + "can be sold, at exactly those prices.")
        @Key("shop.sell-pricing") SellPricing sellPricing,

        @In("economy/shop") @Title("Custom values")
        @Describe("'<item> <value>', comma separated. What an item is worth before any markup, in place "
                + "of the shipped price list. Everything crafted from it is repriced to follow.")
        @Key("shop.values") List<String> customValues,

        @In("economy/shop") @Title("Custom sell prices")
        @Describe("'<item> <price>', comma separated: 'diamond 40, iron_ingot 3'. What the shop pays "
                + "for one. Also set from the shop itself with shift and right click on an item.")
        @Key("shop.sell-prices") List<String> sellPrices,

        @In("economy/shop") @Title("Custom buy prices")
        @Describe("'<item> <price>', comma separated. What one costs to buy, in place of the worked-out "
                + "price. Never lower than its sell price, whichever list says so.")
        @Key("shop.buy-prices") List<String> buyPrices,

        @In("economy/shop") @Title("Never sold to players")
        @Describe("Item names, comma separated. The shop will not sell these, whatever their price.")
        @Key("shop.not-sold") List<String> notSold,

        @In("economy/shop") @Title("Never bought from players")
        @Describe("Item names, comma separated. The shop will not take these.")
        @Key("shop.not-bought") List<String> notBought,

        @In("economy/shop") @Title("Price crafted things from their recipes")
        @Describe("Planks from logs, iron blocks from ingots — every recipe on the server, worked out "
                + "from the prices of raw materials. Off prices only what has a price of its own.")
        @Key("shop.derive-from-recipes") boolean deriveFromRecipes,

        @In("economy/shop") @Title("Crafting adds") @Describe("A fraction of the ingredients' value: 0.1 is ten percent.")
        @Key("shop.craft-markup") double craftMarkup,

        @In("economy/shop") @Title("Smelting adds") @Describe("A fraction, as crafting — the fuel and the wait.")
        @Key("shop.smelt-markup") double smeltMarkup,

        @In("economy/shop") @Title("Supply and demand")
        @Describe("Buying something makes it dearer and selling it cheaper, recovering over time.")
        @Key("features.dynamic-prices") boolean dynamicPrices,

        @In("economy/shop") @Title("Prices move at most") @Describe("A fraction either way: 0.5 is half again or half off.")
        @Key("shop.price-swing") double priceSwing,

        @In("economy/shop") @Title("Each stack traded pushes the price")
        @Describe("How hard one stack bought or sold leans on the price.")
        @Key("shop.pressure-per-stack") double pressurePerStack,

        @In("economy/shop") @Title("Prices recover in")
        @Describe("Hours for half of any push to wear off.")
        @Key("shop.recovery-hours") double recoveryHours,

        @In("economy/shop") @Title("Sell enchanted items")
        @Describe("Enchanted tools, armour and books sell for more than plain ones; worn ones for less.")
        @Key("shop.enchanted-selling") boolean enchantedSelling,

        @In("economy/shop") @Title("Each enchantment level is worth")
        @Describe("Treasure enchantments like Mending count double; curses take value away.")
        @Key("shop.enchant-value") String enchantValue,

        @In("economy/shop/categories") @Title("Building Blocks") @Key("shop.category.building-blocks")
        boolean shopBuildingBlocks,

        @In("economy/shop/categories") @Title("Decoration") @Key("shop.category.decorations")
        boolean shopDecorations,

        @In("economy/shop/categories") @Title("Redstone") @Key("shop.category.redstone")
        boolean shopRedstone,

        @In("economy/shop/categories") @Title("Transport") @Key("shop.category.transportation")
        boolean shopTransportation,

        @In("economy/shop/categories") @Title("Food") @Key("shop.category.food")
        boolean shopFood,

        @In("economy/shop/categories") @Title("Tools") @Key("shop.category.tools")
        boolean shopTools,

        @In("economy/shop/categories") @Title("Combat") @Key("shop.category.combat")
        boolean shopCombat,

        @In("economy/shop/categories") @Title("Brewing") @Key("shop.category.brewing")
        boolean shopBrewing,

        @In("economy/shop/categories") @Title("Everything Else") @Key("shop.category.misc")
        boolean shopMisc,

        // ------------------------------------------------------------------ earning
        @In("economy/earn") @Title("Passive income")
        @Describe("Money for being online, every so many minutes.")
        @Key("features.income") boolean incomeEnabled,

        @In("economy/earn") @Title("Income while playing") @Key("income.amount") String income,

        @In("economy/earn") @Title("Income while away")
        @Describe("What somebody away from the keyboard gets instead. 0 for nothing. Away is what Core says "
                + "(the essentials module's /afk); without it, a player who has not moved for the minutes below.")
        @Key("income.away-amount") String incomeAway,

        @In("economy/earn") @Title("Paid every") @Range(min = 1, max = 1440) @Describe("Minutes online.")
        @Key("income.minutes") int incomeMinutes,

        @In("economy/earn") @Title("Away after") @Range(min = 1, max = 120)
        @Describe("Minutes without moving or looking around — only used when no plugin tells Core who is away.")
        @Key("income.afk-minutes") int afkMinutes,

        @In("economy/earn") @Title("Daily reward") @Describe("/daily, once a day.")
        @Key("features.daily") boolean dailyEnabled,

        @In("economy/earn") @Title("Daily reward") @Key("earn.daily") String daily,

        @In("economy/earn") @Title("Added per day of a streak") @Key("earn.daily-streak-bonus")
        String dailyStreakBonus,

        @In("economy/earn") @Title("A streak counts up to") @Range(min = 1, max = 365)
        @Describe("Days. A missed day starts the streak again.")
        @Key("earn.daily-streak-most") int dailyStreakMost,

        @In("economy/earn") @Title("Pay for advancements") @Key("features.advancement-rewards")
        boolean advancementRewardsEnabled,

        @In("economy/earn") @Title("Per advancement") @Key("earn.advancement") String advancementReward,

        @In("economy/earn") @Title("Most earned per hour")
        @Describe("From passive income and advancements together. 0 for no limit.")
        @Key("earn.hourly-cap") String hourlyCap,

        // ------------------------------------------------------------------ interest
        @In("economy/interest") @Title("Interest") @Describe("Money in the bank grows while you play.")
        @Key("features.interest") boolean interestEnabled,

        @In("economy/interest") @Title("Percent per payout") @Key("interest.percent") double interestPercent,

        @In("economy/interest") @Title("Paid every") @Range(min = 5, max = 10080)
        @Describe("Minutes. Only players online at the time are paid.")
        @Key("interest.minutes") int interestMinutes,

        @In("economy/interest") @Title("Most per payout") @Key("interest.cap") String interestCap,

        // ------------------------------------------------------------------ gambling
        @In("economy/gambling") @Title("Gambling")
        @Describe("Every game of chance at once.")
        @Key("features.gambling") boolean gamblingEnabled,

        @In("economy/gambling") @Title("Coin flips") @Describe("/coinflip: against the house, or a duel between two players.")
        @Key("features.coinflip") boolean coinflipEnabled,

        @In("economy/gambling") @Title("Dice") @Describe("/dice: roll over or under a number you choose.")
        @Key("features.dice") boolean diceEnabled,

        @In("economy/gambling") @Title("Slot machine") @Describe("/slots.")
        @Key("features.slots") boolean slotsEnabled,

        @In("economy/gambling") @Title("Roulette") @Describe("/roulette: red, black, green, numbers, dozens.")
        @Key("features.roulette") boolean rouletteEnabled,

        @In("economy/gambling") @Title("Lottery") @Describe("/lottery: tickets into a pot, one winner per draw.")
        @Key("features.lottery") boolean lotteryEnabled,

        @In("economy/gambling") @Title("Smallest bet") @Key("gamble.min-bet") String minBet,

        @In("economy/gambling") @Title("Largest bet") @Key("gamble.max-bet") String maxBet,

        @In("economy/gambling") @Title("House edge, percent")
        @Describe("What every game keeps on average, 0 to 50. The payouts are worked out from it exactly, so "
                + "this is the real edge, not a guess.")
        @Key("gamble.house-edge-percent") double houseEdgePercent,

        @In("economy/gambling") @Title("Most anybody may lose in a day")
        @Describe("Net losses, counted from midnight. 0 for no limit.")
        @Key("gamble.daily-loss-limit") String dailyLossLimit,

        @In("economy/gambling") @Title("Wait between games") @Range(min = 0, max = 60)
        @Describe("Seconds.")
        @Key("gamble.cooldown-seconds") int gambleCooldownSeconds,

        @In("economy/gambling") @Title("A duel challenge stays open for") @Range(min = 10, max = 600)
        @Describe("Seconds.")
        @Key("gamble.duel-seconds") int duelSeconds,

        @In("economy/gambling") @Title("Lottery ticket") @Key("lottery.ticket-price") String ticketPrice,

        @In("economy/gambling") @Title("Lottery draws every") @Range(min = 1, max = 720)
        @Describe("Hours.")
        @Key("lottery.draw-hours") int drawHours,

        @In("economy/gambling") @Title("Most tickets per player per draw") @Range(min = 1, max = 100000)
        @Key("lottery.most-tickets") int mostTickets,

        @In("economy/gambling") @Title("Lottery keeps, percent")
        @Describe("Of the pot, at every draw — destroyed, a money sink. 0 to 50.")
        @Key("lottery.cut-percent") double lotteryCutPercent,

        // ------------------------------------------------------------------ display
        @In("economy/display") @Title("Balance in the sidebar")
        @Describe("Every player's balance, rank and the richest players on the right of the screen. Each player "
                + "can hide it with /bank sidebar. A minigame's sidebar still wins while it runs.")
        @Key("features.sidebar") boolean sidebarEnabled,

        @In("economy/display") @Title("Richest players in the sidebar") @Range(min = 0, max = 10)
        @Key("sidebar.richest") int sidebarRichest,

        @In("economy/display") @Title("Leaderboards in the world")
        @Describe("Floating lists of the richest players, put down with /eco leaderboard place.")
        @Key("features.leaderboards") boolean leaderboardsEnabled,

        @In("economy/display") @Title("A leaderboard shows") @Range(min = 1, max = 20)
        @Describe("Players.")
        @Key("leaderboard.size") int leaderboardSize,

        @In("economy/display") @Title("Leaderboard places")
        @Describe("'<world> <x> <y> <z>', comma separated. Written by /eco leaderboard place and remove.")
        @Key("leaderboard.spots") List<String> leaderboardSpots,

        // ------------------------------------------------------------------ general
        @In("economy") @Title("Richest players list") @Describe("/baltop.")
        @Key("features.baltop") boolean baltopEnabled) {

    public static final EconomySettings DEFAULTS = new EconomySettings(
            // currency
            "Coin", "Coins", "⛃", Currency.Placement.BEFORE, ",",
            "#ffd700,#ff8c00", "#ffd700,#ff8c00|bold", "#fff3b0",
            // accounts
            "1000", "1000000000000", true, 90,
            // pay
            true, "1", 0.0, "10000", 2, true, true, 5,
            true, 10, 10, 3,
            // cash
            true, Material.GOLD_NUGGET, "", true, true, 0.0, 2304,
            // shop
            true, true, 1.0, 0.4, SellPricing.AUTOMATIC, List.of(), List.of(), List.of(), List.of(), List.of(),
            true, 0.1, 0.15, true, 0.5, 0.02, 12.0, true, "40",
            true, true, true, true, true, true, true, true, true,
            // earning
            false, "10", "2", 30, 5,
            true, "500", "100", 7,
            true, "250", "20000",
            // interest
            true, 0.25, 60, "250",
            // gambling
            true, true, true, true, true, true, "1", "100000", 3.0, "0", 1, 60, "100", 24, 100, 10.0,
            // display
            true, 3, true, 10, List.of(),
            // general
            true);

    /** The currency these settings describe. */
    public Currency currency() {
        char group = firstChar(groupSeparator, ',');
        return new Currency(currencySingular, currencyPlural, currencySymbol, currencyPlacement, 0,
                group, group == '.' ? ',' : '.', true,
                NameStyle.parse(nameStyle), NameStyle.parse(symbolStyle), NameStyle.parse(amountStyle));
    }

    private static char firstChar(String text, char fallback) {
        return text == null || text.isEmpty() ? fallback : text.charAt(0);
    }

    /**
     * An amount written in these settings, read in this currency.
     *
     * @param fallback the shipped default, read the same way — never zero by accident
     */
    public Money money(String written, String fallback) {
        Currency currency = currency();
        return currency.parse(written).or(() -> currency.parse(fallback)).orElse(Money.ZERO);
    }

    public Money starting() {
        return money(startingBalance, DEFAULTS.startingBalance);
    }

    public Money most() {
        Money most = money(mostBalance, DEFAULTS.mostBalance);
        return most.isPositive() ? most : Money.of(Long.MAX_VALUE / 4);
    }

    public Money payMinimumMoney() {
        return money(payMinimum, DEFAULTS.payMinimum);
    }

    public Money payConfirmAboveMoney() {
        return money(payConfirmAbove, DEFAULTS.payConfirmAbove);
    }

    public Money incomeMoney() {
        return money(income, DEFAULTS.income);
    }

    /** May be zero: nothing while away. */
    public Money incomeAwayMoney() {
        return settingOrZero(incomeAway);
    }

    public Money enchantValueMoney() {
        return money(enchantValue, DEFAULTS.enchantValue);
    }

    private Money settingOrZero(String written) {
        return currency().parse(written).orElse(Money.ZERO);
    }

    public Money dailyMoney() {
        return money(daily, DEFAULTS.daily);
    }

    public Money dailyBonusMoney() {
        return money(dailyStreakBonus, DEFAULTS.dailyStreakBonus);
    }

    public Money advancementMoney() {
        return money(advancementReward, DEFAULTS.advancementReward);
    }

    public Money hourlyCapMoney() {
        return settingOrZero(hourlyCap);
    }

    public Money minBetMoney() {
        return money(minBet, DEFAULTS.minBet);
    }

    public Money maxBetMoney() {
        return money(maxBet, DEFAULTS.maxBet);
    }

    public Money dailyLossLimitMoney() {
        return settingOrZero(dailyLossLimit);
    }

    public Money ticketPriceMoney() {
        return money(ticketPrice, DEFAULTS.ticketPrice);
    }

    public double houseEdge() {
        return percent(houseEdgePercent, 50);
    }

    public double lotteryCut() {
        return percent(lotteryCutPercent, 50);
    }

    /** Whether one game may be played: gambling as a whole and that game both switched on. */
    public boolean gameOpen(boolean game) {
        return gamblingEnabled && game;
    }

    public Money interestCapMoney() {
        return money(interestCap, DEFAULTS.interestCap);
    }

    /** Percent as a fraction, kept inside 0–50 %. */
    public double payTax() {
        return percent(payTaxPercent, 50);
    }

    public double withdrawFee() {
        return percent(withdrawFeePercent, 50);
    }

    public double interestRate() {
        return percent(interestPercent, 100);
    }

    private static double percent(double value, double most) {
        if (!(value > 0)) {
            return 0;
        }
        return Math.min(most, value) / 100.0;
    }

    public double buyMarkupClamped() {
        return clamp(buyMarkup, 0.01, 100, 1.0);
    }

    /** Never as much as buying costs, so a round trip through the shop always loses. */
    public double sellRatioClamped() {
        return Math.min(clamp(sellRatio, 0, 100, 0.4), buyMarkupClamped() * 0.95);
    }

    public double craftMarkupClamped() {
        return clamp(craftMarkup, 0, 10, 0.1);
    }

    public double smeltMarkupClamped() {
        return clamp(smeltMarkup, 0, 10, 0.15);
    }

    public double priceSwingClamped() {
        return clamp(priceSwing, 0, 0.95, 0.5);
    }

    public double pressurePerStackClamped() {
        return clamp(pressurePerStack, 0, 1, 0.02);
    }

    public double recoveryHoursClamped() {
        return clamp(recoveryHours, 0.01, 24 * 365, 12);
    }

    private static double clamp(double value, double low, double high, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(low, Math.min(high, value));
    }

    /** Whether this shop category is open. */
    public boolean categoryOpen(Category category) {
        return switch (category) {
            case BUILDING_BLOCKS -> shopBuildingBlocks;
            case DECORATIONS -> shopDecorations;
            case REDSTONE -> shopRedstone;
            case TRANSPORTATION -> shopTransportation;
            case FOOD -> shopFood;
            case TOOLS -> shopTools;
            case COMBAT -> shopCombat;
            case BREWING -> shopBrewing;
            case MISC -> shopMisc;
        };
    }

    /** The settings key that switches a category, for the admin screen. */
    public static String categoryKey(Category category) {
        return "shop.category." + switch (category) {
            case BUILDING_BLOCKS -> "building-blocks";
            case DECORATIONS -> "decorations";
            case REDSTONE -> "redstone";
            case TRANSPORTATION -> "transportation";
            case FOOD -> "food";
            case TOOLS -> "tools";
            case COMBAT -> "combat";
            case BREWING -> "brewing";
            case MISC -> "misc";
        };
    }
}
