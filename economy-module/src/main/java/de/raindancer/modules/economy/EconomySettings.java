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

        @In("economy/currency") @Title("Decimals") @Range(min = 0, max = 4)
        @Describe("Digits after the point. 0 for whole coins only. Changing it later re-reads every "
                + "stored balance in the new unit, so set it before anybody has money.")
        @Key("currency.decimals") int currencyDecimals,

        @In("economy/currency") @Title("Thousands separator") @Describe("1,000 or 1.000 — one character.")
        @Key("currency.group-separator") String groupSeparator,

        @In("economy/currency") @Title("Decimal separator") @Describe("1.50 or 1,50 — one character.")
        @Key("currency.decimal-separator") String decimalSeparator,

        @In("economy/currency") @Title("Hide .00 on whole amounts")
        @Describe("Writes $5 rather than $5.00.")
        @Key("currency.trim-zeros") boolean trimZeros,

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

        // ------------------------------------------------------------------ cash
        @In("economy/cash") @Title("Coins and notes")
        @Describe("Withdrawing money as items you can carry, trade, drop and lose — and paying it back in.")
        @Key("features.cash") boolean cashEnabled,

        @In("economy/cash") @Title("Denominations")
        @Describe("'<value> <material> coin|note', comma separated. Coins stack; notes carry a serial "
                + "number. Coins and notes are never usable as their material — no crafting, smelting, "
                + "trading or bartering.")
        @Key("cash.denominations") List<String> denominations,

        @In("economy/cash") @Title("Cheques") @Describe("A single note for any amount: /withdraw <amount> cheque.")
        @Key("features.cheques") boolean chequesEnabled,

        @In("economy/cash") @Title("Serial numbers on notes")
        @Describe("Every note and cheque is numbered and can be paid in once only, so a duplicated note "
                + "is caught and reported instead of minting money.")
        @Key("cash.serials") boolean serialNotes,

        @In("economy/cash") @Title("Right click pays cash in")
        @Describe("Right clicking a coin or note pays the stack in; sneaking pays in every piece you carry.")
        @Key("cash.right-click") boolean depositOnRightClick,

        @In("economy/cash") @Title("Withdrawal fee, percent") @Describe("0 for none, at most 50.")
        @Key("cash.withdraw-fee-percent") double withdrawFeePercent,

        @In("economy/cash") @Title("Most pieces per withdrawal") @Range(min = 1, max = 2304)
        @Describe("Stops /withdraw 1000000 filling the world with nuggets.")
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
        @In("economy/earn") @Title("Pay for killing mobs") @Key("features.mob-rewards")
        boolean mobRewardsEnabled,

        @In("economy/earn") @Title("Mob rewards")
        @Describe("'<mob> <amount>', comma separated: 'zombie 2, creeper 3'.")
        @Key("earn.mobs") List<String> mobRewards,

        @In("economy/earn") @Title("Mobs from spawners pay")
        @Describe("Off, a mob farm built on a spawner earns nothing.")
        @Key("earn.spawner-mobs-pay") boolean spawnerMobsPay,

        @In("economy/earn") @Title("Pay for mining") @Key("features.mining-rewards")
        boolean miningRewardsEnabled,

        @In("economy/earn") @Title("Mining rewards")
        @Describe("'<block> <amount>', comma separated. A block somebody placed never pays when broken.")
        @Key("earn.mining") List<String> miningRewards,

        @In("economy/earn") @Title("Salary for playing") @Key("features.salary")
        boolean salaryEnabled,

        @In("economy/earn") @Title("Salary") @Key("earn.salary") String salary,

        @In("economy/earn") @Title("Paid every") @Range(min = 1, max = 1440)
        @Describe("Minutes of active play. Time spent away from the keyboard does not count.")
        @Key("earn.salary-minutes") int salaryMinutes,

        @In("economy/earn") @Title("Away after") @Range(min = 1, max = 120)
        @Describe("Minutes without moving or looking around before a player counts as away.")
        @Key("earn.afk-minutes") int afkMinutes,

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
        @Describe("From mobs, mining, salary and advancements together. 0 for no limit.")
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
        @Describe("Every game of chance at once. If players can buy this currency for real money, gambling "
                + "with it may be regulated where the server is run (in Germany: GlüStV) — keep it off then.")
        @Key("features.gambling") boolean gamblingEnabled,

        @In("economy/gambling") @Title("Coin flips") @Describe("/coinflip: against the house, or a duel between two players.")
        @Key("features.coinflip") boolean coinflipEnabled,

        @In("economy/gambling") @Title("Dice") @Describe("/dice: roll over or under a number you choose.")
        @Key("features.dice") boolean diceEnabled,

        @In("economy/gambling") @Title("Slot machine") @Describe("/slots.")
        @Key("features.slots") boolean slotsEnabled,

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

        // ------------------------------------------------------------------ general
        @In("economy") @Title("Richest players list") @Describe("/baltop.")
        @Key("features.baltop") boolean baltopEnabled) {

    public static final EconomySettings DEFAULTS = new EconomySettings(
            // currency
            "Coin", "Coins", "⛃", Currency.Placement.BEFORE, 2, ",", ".", true,
            "#ffd700,#ff8c00", "#ffd700,#ff8c00|bold", "#fff3b0",
            // accounts
            "100", "1000000000000", true, 90,
            // pay
            true, "0.01", 0.0, "1000", 2, true, true, 5,
            // cash
            true, List.of("1 gold_nugget coin", "10 gold_ingot coin", "50 paper note", "100 paper note",
                    "500 paper note", "1000 paper note"),
            true, true, true, 0.0, 576,
            // shop
            true, true, 1.0, 0.4, SellPricing.AUTOMATIC, List.of(), List.of(), List.of(), List.of(), List.of(),
            true, 0.1, 0.15, true, 0.5, 0.02, 12.0,
            true, true, true, true, true, true, true, true, true,
            // earning
            true, List.of("zombie 2", "zombie_villager 2", "husk 2", "drowned 2", "skeleton 2", "stray 2",
                    "bogged 2", "spider 2", "cave_spider 2", "creeper 3", "enderman 5", "witch 5", "slime 1",
                    "magma_cube 1", "blaze 4", "ghast 6", "wither_skeleton 6", "piglin_brute 8", "hoglin 3",
                    "zoglin 3", "guardian 4", "elder_guardian 150", "phantom 3", "pillager 3", "vindicator 5",
                    "evoker 15", "vex 2", "ravager 25", "shulker 6", "breeze 5", "silverfish 0.5",
                    "endermite 1", "warden 250", "wither 500", "ender_dragon 1000"),
            false,
            true, List.of("coal_ore 0.5", "deepslate_coal_ore 0.75", "copper_ore 0.5",
                    "deepslate_copper_ore 0.75", "iron_ore 1", "deepslate_iron_ore 1.5", "gold_ore 2",
                    "deepslate_gold_ore 3", "nether_gold_ore 0.5", "redstone_ore 1", "deepslate_redstone_ore 1.5",
                    "lapis_ore 2", "deepslate_lapis_ore 3", "diamond_ore 10", "deepslate_diamond_ore 12",
                    "emerald_ore 15", "deepslate_emerald_ore 18", "nether_quartz_ore 0.5", "ancient_debris 40"),
            true, "10", 30, 5,
            true, "50", "10", 7,
            true, "25", "2000",
            // interest
            true, 0.25, 60, "25",
            // gambling
            true, true, true, true, true, "1", "10000", 3.0, "0", 1, 60, "10", 24, 100, 10.0,
            // general
            true);

    /** The currency these settings describe. */
    public Currency currency() {
        return new Currency(currencySingular, currencyPlural, currencySymbol, currencyPlacement, currencyDecimals,
                firstChar(groupSeparator, ','), firstChar(decimalSeparator, '.'), trimZeros,
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

    public Money salaryMoney() {
        return money(salary, DEFAULTS.salary);
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
        return money(hourlyCap, DEFAULTS.hourlyCap);
    }

    public Money minBetMoney() {
        return money(minBet, DEFAULTS.minBet);
    }

    public Money maxBetMoney() {
        return money(maxBet, DEFAULTS.maxBet);
    }

    public Money dailyLossLimitMoney() {
        return money(dailyLossLimit, DEFAULTS.dailyLossLimit);
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
