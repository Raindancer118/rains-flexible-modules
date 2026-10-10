package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.Describe;
import io.papermc.paper.advancement.AdvancementDisplay;
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
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.model.NaturalPay;
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
        @Topic(path = "economy/experience", title = "Experience", icon = Material.EXPERIENCE_BOTTLE),
        @Topic(path = "economy/shop/categories", title = "Shop categories", icon = Material.CHEST),
        @Topic(path = "economy/earn", title = "Earning", icon = Material.DIAMOND_PICKAXE),
        @Topic(path = "economy/interest", title = "Interest", icon = Material.CLOCK),
        @Topic(path = "economy/shop/spawn-eggs", title = "Spawn eggs", icon = Material.PIG_SPAWN_EGG),
        @Topic(path = "economy/gambling", title = "Gambling", icon = Material.GOLD_BLOCK),
        @Topic(path = "economy/gambling/coinflip", title = "Coin flip", icon = Material.SUNFLOWER),
        @Topic(path = "economy/gambling/dice", title = "Dice", icon = Material.WHITE_WOOL),
        @Topic(path = "economy/gambling/slots", title = "Slot machine", icon = Material.DIAMOND),
        @Topic(path = "economy/gambling/roulette", title = "Roulette", icon = Material.ENDER_PEARL),
        @Topic(path = "economy/gambling/blackjack", title = "Blackjack", icon = Material.PAPER),
        @Topic(path = "economy/gambling/baccarat", title = "Baccarat", icon = Material.RED_CONCRETE),
        @Topic(path = "economy/gambling/hilo", title = "Hi-Lo", icon = Material.LIME_CONCRETE),
        @Topic(path = "economy/gambling/mines", title = "Mines", icon = Material.TNT),
        @Topic(path = "economy/gambling/crash", title = "Crash", icon = Material.FIREWORK_ROCKET),
        @Topic(path = "economy/gambling/race", title = "Horse race", icon = Material.SADDLE),
        @Topic(path = "economy/gambling/scratch", title = "Scratch card", icon = Material.MAP),
        @Topic(path = "economy/gambling/lottery", title = "Lottery", icon = Material.FILLED_MAP),
        @Topic(path = "economy/auctions", title = "Auctions", icon = Material.BELL),
        @Topic(path = "economy/raffles", title = "Raffles", icon = Material.NAME_TAG),
        @Topic(path = "economy/loans", title = "Loans", icon = Material.GOLD_INGOT),
        @Topic(path = "economy/tax", title = "Wealth tax", icon = Material.IRON_BARS),
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

        @In("economy/shop") @Title("Sold off, worth at least")
        @Describe("Selling the same item again and again makes it cheaper, down to this times its normal price: 0.5 "
                + "is half. Between 0.05 and 1.")
        @Key("shop.lowest-multiplier") double lowestMultiplier,

        @In("economy/shop") @Title("Bought up, costs at most")
        @Describe("Buying an item a lot while nobody sells it makes it dearer, up to this times its normal price: 2 "
                + "is double. Between 1 and 10.")
        @Key("shop.highest-multiplier") double highestMultiplier,

        @In("economy/shop") @Title("Each stack traded pushes the price")
        @Describe("How hard one stack bought or sold leans on the price.")
        @Key("shop.pressure-per-stack") double pressurePerStack,

        @In("economy/shop") @Title("Prices recover in")
        @Describe("Hours for half of any push to wear off.")
        @Key("shop.recovery-hours") double recoveryHours,

        @In("economy/shop") @Title("Enchanted books for sale")
        @Describe("An Enchantments drawer in the shop: any enchantment at any level up to its normal maximum, as a book.")
        @Key("shop.enchant-books") boolean enchantBooks,

        @In("economy/shop") @Title("A level of enchantment costs")
        @Describe("A level of an ordinary enchantment, on a book bought from the shop. Useful ones cost more "
                + "(see 'How useful each enchantment is').")
        @Key("shop.enchant-price") String enchantPrice,

        @In("economy/shop") @Title("Treasure enchantments for sale")
        @Describe("Mending, Frost Walker, Soul Speed and the others otherwise found only as loot, priced by how "
                + "useful they are. Selling them to the shop works either way.")
        @Key("shop.enchant-treasure") boolean enchantTreasure,

        @In("economy/shop") @Title("Enchantments not for sale")
        @Describe("Their keys, like sharpness or mending, comma separated.")
        @Key("shop.enchant-closed") List<String> enchantClosed,

        @In("economy/shop") @Title("Sell enchanted items")
        @Describe("Enchanted tools, armour and books sell for more than plain ones; worn ones for less.")
        @Key("shop.enchanted-selling") boolean enchantedSelling,

        @In("economy/shop") @Title("Each enchantment level is worth")
        @Describe("What the shop pays for a level of an ordinary enchantment. Useful ones are worth more "
                + "(see 'How useful each enchantment is'); curses take value away.")
        @Key("shop.enchant-value") String enchantValue,

        @In("economy/shop") @Title("How useful each enchantment is")
        @Describe("Changes to the built-in ranking, one per line like 'mending 12': what an enchantment is worth "
                + "at its highest level, in levels of an ordinary one. Built in: Mending 12, Protection and "
                + "Efficiency 10, Sharpness, Unbreaking and Fortune 9 … down to Knockback, Punch and Bane of "
                + "Arthropods 2. Buying and selling prices both follow it; the shop's drawer is sorted by it.")
        @Key("shop.enchant-worth") List<String> enchantWorth,

        @In("economy/shop/spawn-eggs") @Title("Spawn eggs for sale")
        @Describe("A Spawn eggs drawer in the shop: every mob's egg at the price below.")
        @Key("shop.spawn-eggs") boolean spawnEggs,

        @In("economy/shop/spawn-eggs") @Title("A spawn egg is worth")
        @Describe("Before the shop's markup. A single egg can be priced on its own in /eco → Shop items.")
        @Key("shop.spawn-egg-value") String spawnEggValue,

        @In("economy/shop/spawn-eggs") @Title("Eggs not for sale")
        @Describe("Mob names, comma separated: wither, ender_dragon.")
        @Key("shop.spawn-eggs-closed") List<String> spawnEggsClosed,

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

        @In("economy/earn") @Title("Per advancement")
        @Describe("An ordinary one — a task, like Stone Age.")
        @Key("earn.advancement") String advancementReward,

        @In("economy/earn") @Title("Per goal advancement")
        @Describe("The harder ones with the rounded frame, like Hot Stuff or The End?")
        @Key("earn.advancement-goal") String advancementGoalReward,

        @In("economy/earn") @Title("Per challenge advancement")
        @Describe("The hardest, purple ones, like Return to Sender or How Did We Get Here?")
        @Key("earn.advancement-challenge") String advancementChallengeReward,

        @In("economy/earn") @Title("Tell players what an advancement paid")
        @Describe("A chat line with the amount whenever an advancement pays.")
        @Key("earn.advancement-tell") boolean advancementTell,

        @In("economy/earn") @Title("Back pay for earlier advancements")
        @Describe("Once per player, /claimadvancements pays for advancements made before they paid — or tops up "
                + "ones paid less than they pay now. Players who have some waiting are told when they join.")
        @Key("earn.advancement-backpay") boolean advancementBackpay,

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
        @Describe("Every game of chance at once. Each game also has a switch and a page of its own.")
        @Key("features.gambling") boolean gamblingEnabled,

        @In("economy/gambling") @Title("Smallest bet") @Describe("At any game without one of its own.")
        @Key("gamble.min-bet") String minBet,

        @In("economy/gambling") @Title("House edge, percent")
        @Describe("What the casino keeps on average, 0 to 50, at every game without an edge of its own. The "
                + "payouts are worked out from it exactly, so this is the real edge, not a guess.")
        @Key("gamble.house-edge-percent") double houseEdgePercent,

        @In("economy/gambling") @Title("Most anybody may lose in a day")
        @Describe("Net losses, counted from midnight. 0 for no limit.")
        @Key("gamble.daily-loss-limit") String dailyLossLimit,

        @In("economy/gambling") @Title("Wait between games") @Range(min = 0, max = 60)
        @Describe("Seconds.")
        @Key("gamble.cooldown-seconds") int gambleCooldownSeconds,

        @In("economy/gambling") @Title("Dealers wear the skin of")
        @Describe("A player name. Empty: a black suit with a red tie.")
        @Key("casino.dealer-skin") String dealerSkin,

        @In("economy/gambling/coinflip") @Title("Coin flips") @Describe("/coinflip: against the house, or a duel between two players.")
        @Key("features.coinflip") boolean coinflipEnabled,

        @In("economy/gambling/coinflip") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("coinflip.min-bet") String coinflipMinBet,

        @In("economy/gambling/coinflip") @Title("The house keeps, percent")
        @Describe("What a coin flip keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("coinflip.house-edge-percent") String coinflipEdge,

        @In("economy/gambling/coinflip") @Title("A duel challenge stays open for") @Range(min = 10, max = 600)
        @Describe("Seconds. In a duel the house keeps its edge of the pot.")
        @Key("gamble.duel-seconds") int duelSeconds,

        @In("economy/gambling/dice") @Title("Dice") @Describe("/dice: roll over or under a number you choose.")
        @Key("features.dice") boolean diceEnabled,

        @In("economy/gambling/dice") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("dice.min-bet") String diceMinBet,

        @In("economy/gambling/dice") @Title("The house keeps, percent")
        @Describe("What dice keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("dice.house-edge-percent") String diceEdge,

        @In("economy/gambling/slots") @Title("Slot machine") @Describe("/slots.")
        @Key("features.slots") boolean slotsEnabled,

        @In("economy/gambling/slots") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("slots.min-bet") String slotsMinBet,

        @In("economy/gambling/slots") @Title("The house keeps, percent")
        @Describe("What the slot machine keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("slots.house-edge-percent") String slotsEdge,

        @In("economy/gambling/roulette") @Title("Roulette") @Describe("/roulette: red, black, green, numbers, dozens.")
        @Key("features.roulette") boolean rouletteEnabled,

        @In("economy/gambling/roulette") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("roulette.min-bet") String rouletteMinBet,

        @In("economy/gambling/roulette") @Title("The house keeps, percent")
        @Describe("What roulette keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("roulette.house-edge-percent") String rouletteEdge,

        @In("economy/gambling/blackjack") @Title("Blackjack") @Describe("Against the dealer: hit, stand, double, split.")
        @Key("features.blackjack") boolean blackjackEnabled,

        @In("economy/gambling/blackjack") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("blackjack.min-bet") String blackjackMinBet,

        @In("economy/gambling/blackjack") @Title("A natural pays")
        @Describe("Three to two is the fair table, about half a percent to the house. Six to five takes about 1.4 "
                + "percent more, even money about 2.3 percent more.")
        @Key("blackjack.natural-pays") NaturalPay naturalPays,

        @In("economy/gambling/blackjack") @Title("The dealer hits a soft 17")
        @Describe("Another 0.2 percent to the house.")
        @Key("blackjack.dealer-hits-soft-17") boolean dealerHitsSoft17,

        @In("economy/gambling/blackjack") @Title("Decks in a shoe") @Range(min = 1, max = 8)
        @Describe("For blackjack, baccarat and Hi-Lo.")
        @Key("casino.decks") int decks,

        @In("economy/gambling/baccarat") @Title("Baccarat") @Describe("Player, banker or tie, with the real third-card rules.")
        @Key("features.baccarat") boolean baccaratEnabled,

        @In("economy/gambling/baccarat") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("baccarat.min-bet") String baccaratMinBet,

        @In("economy/gambling/baccarat") @Title("A tie pays, to one") @Range(min = 5, max = 9)
        @Describe("8 is the usual table (about 14 percent to the house on a tie bet), 9 the generous one.")
        @Key("baccarat.tie-pays") int baccaratTiePays,

        @In("economy/gambling/baccarat") @Title("Commission on a banker win, percent")
        @Describe("5 is the usual table, about 1 percent to the house. 0 to 50.")
        @Key("baccarat.banker-commission-percent") double baccaratCommissionPercent,

        @In("economy/gambling/hilo") @Title("Hi-Lo") @Describe("Higher or lower than the card showing; cash out any time.")
        @Key("features.hilo") boolean hiloEnabled,

        @In("economy/gambling/hilo") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("hilo.min-bet") String hiloMinBet,

        @In("economy/gambling/hilo") @Title("The house keeps, percent")
        @Describe("What Hi-Lo keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("hilo.house-edge-percent") String hiloEdge,

        @In("economy/gambling/mines") @Title("Mines") @Describe("Clear tiles on a field hiding mines; cash out any time.")
        @Key("features.mines") boolean minesEnabled,

        @In("economy/gambling/mines") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("mines.min-bet") String minesMinBet,

        @In("economy/gambling/mines") @Title("The house keeps, percent")
        @Describe("What mines keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("mines.house-edge-percent") String minesEdge,

        @In("economy/gambling/crash") @Title("Crash") @Describe("One round for everybody: a multiplier climbs until it crashes; cash out before.")
        @Key("features.crash") boolean crashEnabled,

        @In("economy/gambling/crash") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("crash.min-bet") String crashMinBet,

        @In("economy/gambling/crash") @Title("The house keeps, percent")
        @Describe("What crash keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("crash.house-edge-percent") String crashEdge,

        @In("economy/gambling/crash") @Title("Bets for a round close after") @Range(min = 3, max = 120)
        @Describe("Seconds.")
        @Key("crash.betting-seconds") int crashBettingSeconds,

        @In("economy/gambling/crash") @Title("A round goes no higher than") @Range(min = 2, max = 10000)
        @Describe("Times the stake. 1,000 is about two minutes of climbing.")
        @Key("crash.most-multiplier") int crashMost,

        @In("economy/gambling/race") @Title("Horse races") @Describe("One race for everybody: bet on a horse and watch it run.")
        @Key("features.race") boolean raceEnabled,

        @In("economy/gambling/race") @Title("Smallest bet")
        @Describe("Empty: the casino's smallest bet.")
        @Key("race.min-bet") String raceMinBet,

        @In("economy/gambling/race") @Title("The house keeps, percent")
        @Describe("What a horse race keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("race.house-edge-percent") String raceEdge,

        @In("economy/gambling/race") @Title("Bets close after") @Range(min = 10, max = 600)
        @Describe("Seconds.")
        @Key("race.betting-seconds") int raceBettingSeconds,

        @In("economy/gambling/scratch") @Title("Scratch cards") @Describe("Tickets you buy, carry, give away and scratch.")
        @Key("features.scratch") boolean scratchEnabled,

        @In("economy/gambling/scratch") @Title("A scratch card costs") @Key("scratch.price") String scratchPrice,

        @In("economy/gambling/scratch") @Title("The house keeps, percent")
        @Describe("What a scratch card keeps on average, 0 to 50 — the payouts are worked out from it exactly. "
                + "Empty: the casino's house edge.")
        @Key("scratch.house-edge-percent") String scratchEdge,

        @In("economy/gambling/lottery") @Title("Lottery") @Describe("/lottery: tickets into a pot, one winner per draw.")
        @Key("features.lottery") boolean lotteryEnabled,

        @In("economy/gambling/lottery") @Title("A ticket costs") @Key("lottery.ticket-price") String ticketPrice,

        @In("economy/gambling/lottery") @Title("Draws every") @Range(min = 1, max = 720)
        @Describe("Hours.")
        @Key("lottery.draw-hours") int drawHours,

        @In("economy/gambling/lottery") @Title("Most tickets per player per draw") @Range(min = 0, max = 100000)
        @Describe("0 for no limit.")
        @Key("lottery.most-tickets") int mostTickets,

        @In("economy/gambling/lottery") @Title("Numbers on a ticket") @Range(min = 2, max = 8)
        @Describe("How many numbers a ticket has, and how many balls are drawn.")
        @Key("lottery.pick") int lotteryPick,

        @In("economy/gambling/lottery") @Title("Numbers go up to") @Range(min = 10, max = 60)
        @Describe("Picked from 1 to this. 4 from 20 is a jackpot in 4,845; 6 from 49 is one in 14 million.")
        @Key("lottery.numbers") int lotteryNumbers,

        @In("economy/gambling/lottery") @Title("The lottery keeps, percent")
        @Describe("Of every ticket's price — destroyed, a money sink; the rest goes into the pot. 0 to 50.")
        @Key("lottery.cut-percent") double lotteryCutPercent,

        // ------------------------------------------------------------------ auctions
        @In("economy/auctions") @Title("Auctions")
        @Describe("Players put up an item from their hand; one auction at a time for the whole server, announced "
                + "in chat with a countdown, the bids on a boss bar. The rest wait in a queue.")
        @Key("features.auctions") boolean auctionsEnabled,

        @In("economy/auctions") @Title("An auction runs, unless the seller says otherwise") @Range(min = 30, max = 3600)
        @Describe("Seconds.")
        @Key("auction.default-seconds") int auctionDefaultSeconds,

        @In("economy/auctions") @Title("Shortest a seller may choose") @Range(min = 30, max = 3600)
        @Describe("Seconds.")
        @Key("auction.min-seconds") int auctionMinSeconds,

        @In("economy/auctions") @Title("Longest a seller may choose") @Range(min = 30, max = 3600)
        @Describe("Seconds.")
        @Key("auction.max-seconds") int auctionMaxSeconds,

        @In("economy/auctions") @Title("Lowest starting price") @Key("auction.smallest-start") String auctionSmallestStart,

        @In("economy/auctions") @Title("A bid must beat the last by at least")
        @Describe("Or by the percentage below, whichever is more.")
        @Key("auction.step") String auctionStep,

        @In("economy/auctions") @Title("…or by this percentage of it") @Key("auction.step-percent") double auctionStepPercent,

        @In("economy/auctions") @Title("A late bid gives everybody this long again") @Range(min = 0, max = 120)
        @Describe("Seconds. A bid with less time left than this pushes the end back to it, so nobody wins by "
                + "bidding in the last second. 0 to switch off.")
        @Key("auction.snipe-seconds") int auctionSnipeSeconds,

        @In("economy/auctions") @Title("Pause between two auctions") @Range(min = 0, max = 600)
        @Describe("Seconds.")
        @Key("auction.gap-seconds") int auctionGapSeconds,

        @In("economy/auctions") @Title("Listing an item costs")
        @Describe("Paid when the item goes up, sold or not; the money leaves the economy.")
        @Key("auction.listing-fee") String auctionListingFee,

        @In("economy/auctions") @Title("The auction house keeps, percent")
        @Describe("Of the price an item sells for; the money leaves the economy. 0 to 50.")
        @Key("auction.fee-percent") double auctionFeePercent,

        @In("economy/auctions") @Title("Auctions waiting at most") @Range(min = 1, max = 100)
        @Key("auction.queue-size") int auctionQueueSize,

        @In("economy/auctions") @Title("Auctions per player at once") @Range(min = 1, max = 20)
        @Describe("Live and waiting together.")
        @Key("auction.per-player") int auctionsPerPlayer,

        @In("economy/auctions") @Title("Every bid in chat")
        @Describe("Off: only the start, the countdown and the result are announced.")
        @Key("auction.announce-bids") boolean auctionAnnounceBids,

        @In("economy/auctions") @Title("The auction on a boss bar")
        @Describe("The item, the highest bid and the time left, for everybody who has not muted auctions.")
        @Key("auction.bossbar") boolean auctionBossBar,

        // ------------------------------------------------------------------ raffles
        @In("economy/raffles") @Title("Raffles")
        @Describe("A player raffles off an item from their hand: tickets sold for a while, then one drawn. Several "
                + "can run at once. Staff raffle off money with /eco raffle.")
        @Key("features.raffles") boolean rafflesEnabled,

        @In("economy/raffles") @Title("Giveaways")
        @Describe("Like a raffle, but free to join, once each: a player gives away an item or money, staff give away "
                + "server money with /eco giveaway.")
        @Key("features.giveaways") boolean giveawaysEnabled,

        @In("economy/raffles") @Title("Starting a raffle costs")
        @Describe("Paid by the player who starts it; the money leaves the economy.")
        @Key("raffle.listing-fee") String raffleListingFee,

        @In("economy/raffles") @Title("The house keeps, percent")
        @Describe("Of the tickets' money, before the rest goes to the player who raffled. 0 to 50.")
        @Key("raffle.fee-percent") double raffleFeePercent,

        @In("economy/raffles") @Title("A raffle runs, unless its host says otherwise") @Range(min = 1, max = 10080)
        @Describe("Minutes.")
        @Key("raffle.default-minutes") int raffleDefaultMinutes,

        @In("economy/raffles") @Title("Shortest a host may choose") @Range(min = 1, max = 10080)
        @Describe("Minutes.")
        @Key("raffle.min-minutes") int raffleMinMinutes,

        @In("economy/raffles") @Title("Longest a host may choose") @Range(min = 1, max = 10080)
        @Describe("Minutes.")
        @Key("raffle.max-minutes") int raffleMaxMinutes,

        @In("economy/raffles") @Title("Raffles running at once, at most") @Range(min = 1, max = 100)
        @Key("raffle.most-running") int raffleMostRunning,

        @In("economy/raffles") @Title("Raffles one player may host at once") @Range(min = 1, max = 20)
        @Key("raffle.per-host") int rafflesPerHost,

        @In("economy/raffles") @Title("Cheapest ticket") @Key("raffle.smallest-ticket") String raffleSmallestTicket,

        // ------------------------------------------------------------------ experience
        @In("economy/experience") @Title("Buying and selling experience")
        @Describe("Levels to buy in the shop, and an experience bottle to sell in /sell, priced per point — a level "
                + "near 30 holds far more points than one near 5.")
        @Key("features.xp-trade") boolean xpTradeEnabled,

        @In("economy/experience") @Title("A point of experience costs") @Key("xp.buy-per-point") String xpBuy,

        @In("economy/experience") @Title("A point of experience sells for")
        @Describe("Never more than it costs, whatever is written here, so buying and selling cannot make money.")
        @Key("xp.sell-per-point") String xpSell,

        // ------------------------------------------------------------------ loans
        @In("economy/loans") @Title("Loans")
        @Describe("Players borrow from the bank in /bank → Loan or /loan: one loan at a time, interest added once, "
                + "paid back whenever they like. Once due, the bank takes what the balance holds until it is paid.")
        @Key("features.loans") boolean loansEnabled,

        @In("economy/loans") @Title("Smallest loan") @Key("loans.least") String loanLeast,

        @In("economy/loans") @Title("Largest loan")
        @Describe("For anybody, however good their record. 0 for no cap.")
        @Key("loans.most") String loanMost,

        @In("economy/loans") @Title("A limit of their own")
        @Describe("Each player can borrow up to what they have, plus a quarter of what they earned in their last "
                + "hours of play — less the more of it they spent and gambled away, more for every loan paid back on "
                + "time, less for every late one. Never past the largest loan. Off: everybody may borrow the largest loan.")
        @Key("loans.personal-limit") boolean loanPersonalLimit,

        @In("economy/loans") @Title("Counted: the last … hours of play") @Range(min = 1, max = 168)
        @Describe("Only what was earned, spent, won and lost in this much playtime (not away) counts toward the "
                + "limit. Hours played, not hours on the clock.")
        @Key("loans.recent-hours") int loanRecentHoursSetting,

        @In("economy/loans") @Title("Interest, percent")
        @Describe("Added once when borrowing: 10 means borrowing 1,000 costs 1,100 to pay back. 0 to 100.")
        @Key("loans.interest-percent") double loanInterestPercent,

        @In("economy/loans") @Title("Due after") @Range(min = 1, max = 365)
        @Describe("Days.")
        @Key("loans.days") int loanDays,

        @In("economy/loans") @Title("Late fee per day, percent")
        @Describe("Added to what is still owed for every whole day past the due date. 0 to 50.")
        @Key("loans.late-percent") double loanLatePercent,

        @In("economy/loans") @Title("No gambling while a loan is overdue")
        @Key("loans.overdue-stops-gambling") boolean overdueStopsGambling,

        // ------------------------------------------------------------------ wealth tax
        @In("economy/tax") @Title("Wealth tax")
        @Describe("Takes a percentage of every player's bank balance at an interval and destroys it — a money sink. "
                + "Frozen accounts pay too. Switching it on starts its clock; the first run is one interval later.")
        @Key("features.wealth-tax") boolean wealthTaxEnabled,

        @In("economy/tax") @Title("Percent of a balance") @Describe("0 to 100.")
        @Key("wealth-tax.percent") double wealthTaxPercent,

        @In("economy/tax") @Title("Taken every") @Range(min = 1, max = 8760)
        @Describe("Hours.")
        @Key("wealth-tax.every-hours") int wealthTaxHours,

        @In("economy/tax") @Title("Tax free up to")
        @Describe("Only what is above this is taxed. 0 to tax every coin.")
        @Key("wealth-tax.allowance") String wealthTaxAllowance,

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
        @Key("features.baltop") boolean baltopEnabled,

        // ------------------------------------------------------------------ packs
        @In("economy/shop") @Title("Packs for sale")
        @Describe("A Packs drawer in the shop: ready-made bundles (an explorer's, a builder's…) written in "
                + "packs.yml. A pack is an item, right clicked to unpack, so it can be given away or kept.")
        @Key("features.packs") boolean packsEnabled,

        @In("economy/shop") @Title("A pack is cheaper by") @Range(min = 0, max = 90)
        @Describe("Percent off what its contents cost one by one, at each buyer's own prices. A pack with "
                + "its own price in packs.yml costs exactly that.")
        @Key("shop.pack-discount-percent") int packDiscountPercent,

        // ------------------------------------------------------------------ bulk
        @In("economy/shop") @Title("Cheaper in bulk")
        @Describe("Building blocks and iron (the list below) cost less bought in quantity. Never the "
                + "valuable things: a discount on diamonds would be a discount on money.")
        @Key("features.bulk-discount") boolean bulkDiscount,

        @In("economy/shop") @Title("Bulk discount steps")
        @Describe("'<how many> <percent>', comma separated: '128 5, 640 10' is 5% off from two stacks, "
                + "10% from ten. Bought at once, in one purchase.")
        @Key("shop.bulk-tiers") List<String> bulkTiers,

        @In("economy/shop") @Title("Bulk discount at most") @Range(min = 0, max = 30)
        @Describe("The most bulk ever takes off, in percent, whatever the steps say. Never more than 30.")
        @Key("shop.bulk-most-percent") int bulkMostPercent,

        @In("economy/shop") @Title("Cheaper in bulk")
        @Describe("Shop drawers (building_blocks, decorations…) and items ('iron_ingot', '*_planks'), comma "
                + "separated; '!diamond_block' leaves one out.")
        @Key("shop.bulk-items") List<String> bulkItems) {

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
            true, 0.1, 0.15, true, 0.5, 2.0, 0.02, 12.0, true, "500", true, List.of(), true, "40", List.of(),
            true, "2000", List.of("ender_dragon", "wither"),
            true, true, true, true, true, true, true, true, true,
            // earning
            false, "10", "2", 30, 5,
            true, "500", "100", 7,
            true, "250", "750", "2000", true, true, "20000",
            // interest
            true, 0.25, 60, "250",
            // gambling
            true, "1", 3.0, "0", 0, "",
            true, "", "", 60,
            true, "", "",
            true, "", "",
            true, "", "",
            true, "", NaturalPay.THREE_TO_TWO, false, 6,
            true, "", 8, 5.0,
            true, "", "",
            true, "", "",
            true, "", "", 10, 1000,
            true, "", "", 45,
            true, "50", "",
            true, "100", 24, 0, 4, 20, 10.0,
            // auctions
            true, 120, 60, 600, "10", "10", 5.0, 20, 15, "1000", 5.0, 10, 2, true, true,
            // raffles
            true, true, "0", 5.0, 30, 5, 1440, 5, 1, "1",
            // experience
            true, "3", "1",
            // loans
            true, "100", "10000", true, 12, 10.0, 7, 2.0, true,
            // wealth tax
            false, 1.0, 24, "0",
            // display
            true, 3, true, 10, List.of(),
            // general
            true,
            // packs
            true, 10,
            // bulk
            true, List.of("128 5", "640 10", "1280 15", "1920 20"), 20,
            List.of("building_blocks", "iron_ingot", "iron_nugget", "raw_iron", "*glass*", "*glass_pane",
                    "*_planks", "scaffolding", "ladder", "torch", "lantern", "*_wool", "*_carpet", "*_concrete",
                    "*_concrete_powder", "*terracotta", "rail",
                    "!*_ore", "!ancient_debris", "!diamond_block", "!emerald_block", "!gold_block",
                    "!netherite_block", "!lapis_block", "!redstone_block", "!coal_block", "!raw_gold_block",
                    "!raw_copper_block", "!amethyst_block", "!glass_bottle", "!*_shulker_box"));

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

    public de.raindancer.modules.economy.rules.EnchantWorthRule enchantWorthTable() {
        return new de.raindancer.modules.economy.rules.EnchantWorthRule(enchantWorth);
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

    /** What an advancement pays by how hard it is: tasks least, challenges most. */
    public Money advancementMoney(AdvancementDisplay.Frame frame) {
        return switch (frame) {
            case GOAL -> money(advancementGoalReward, DEFAULTS.advancementGoalReward);
            case CHALLENGE -> money(advancementChallengeReward, DEFAULTS.advancementChallengeReward);
            case null, default -> advancementMoney();
        };
    }

    public Money hourlyCapMoney() {
        return settingOrZero(hourlyCap);
    }

    public Money minBetMoney() {
        return money(minBet, DEFAULTS.minBet);
    }


    public Money dailyLossLimitMoney() {
        return settingOrZero(dailyLossLimit);
    }

    public Money scratchPriceMoney() {
        return money(scratchPrice, DEFAULTS.scratchPrice);
    }

    public Money auctionSmallestStartMoney() {
        return money(auctionSmallestStart, DEFAULTS.auctionSmallestStart);
    }

    public Money auctionStepMoney() {
        return money(auctionStep, DEFAULTS.auctionStep);
    }

    public Money auctionListingFeeMoney() {
        return money(auctionListingFee, DEFAULTS.auctionListingFee);
    }

    /** The sale fee as a percentage, 0 to 50. */
    public double auctionFee() {
        return Math.max(0, Math.min(50, auctionFeePercent));
    }

    public Money raffleListingFeeMoney() {
        return money(raffleListingFee, DEFAULTS.raffleListingFee);
    }

    public Money raffleSmallestTicketMoney() {
        return money(raffleSmallestTicket, DEFAULTS.raffleSmallestTicket);
    }

    public Money enchantPriceMoney() {
        return money(enchantPrice, DEFAULTS.enchantPrice);
    }

    public Money xpBuyMoney() {
        return money(xpBuy, DEFAULTS.xpBuy);
    }

    /** What a point sells for — never more than it costs. */
    public Money xpSellMoney() {
        return money(xpSell, DEFAULTS.xpSell).min(xpBuyMoney());
    }

    public Money wealthTaxAllowanceMoney() {
        return money(wealthTaxAllowance, DEFAULTS.wealthTaxAllowance);
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

    public Money loanLeastMoney() {
        return money(loanLeast, DEFAULTS.loanLeast);
    }

    /** Clamped to what {@code AccountBook} keeps. */
    public int loanRecentHours() {
        return Math.max(1, Math.min(168, loanRecentHoursSetting));
    }

    public Money loanMostMoney() {
        return money(loanMost, DEFAULTS.loanMost);
    }

    public double loanInterest() {
        return percent(loanInterestPercent, 100);
    }

    public double loanLate() {
        return percent(loanLatePercent, 50);
    }

    public Money spawnEggValueMoney() {
        return money(spawnEggValue, DEFAULTS.spawnEggValue);
    }

    /** Whether a game may be played: gambling as a whole and that game both switched on. */
    public boolean gameOn(Game game) {
        return gameOpen(switch (game) {
            case COINFLIP -> coinflipEnabled;
            case DICE -> diceEnabled;
            case SLOTS -> slotsEnabled;
            case ROULETTE -> rouletteEnabled;
            case BLACKJACK -> blackjackEnabled;
            case BACCARAT -> baccaratEnabled;
            case HILO -> hiloEnabled;
            case MINES -> minesEnabled;
            case CRASH -> crashEnabled;
            case RACE -> raceEnabled;
            case SCRATCH -> scratchEnabled;
            case LOTTERY -> lotteryEnabled;
        });
    }

    /** A game's smallest bet: its own, else the casino's. */
    public Money minBet(Game game) {
        return ownMoney(switch (game) {
            case COINFLIP -> coinflipMinBet;
            case DICE -> diceMinBet;
            case SLOTS -> slotsMinBet;
            case ROULETTE -> rouletteMinBet;
            case BLACKJACK -> blackjackMinBet;
            case BACCARAT -> baccaratMinBet;
            case HILO -> hiloMinBet;
            case MINES -> minesMinBet;
            case CRASH -> crashMinBet;
            case RACE -> raceMinBet;
            case SCRATCH, LOTTERY -> "";
        }).orElseGet(this::minBetMoney);
    }


    /** A game's house edge as a fraction: its own, else the casino's. */
    public double edge(Game game) {
        String own = switch (game) {
            case COINFLIP -> coinflipEdge;
            case DICE -> diceEdge;
            case SLOTS -> slotsEdge;
            case ROULETTE -> rouletteEdge;
            case HILO -> hiloEdge;
            case MINES -> minesEdge;
            case CRASH -> crashEdge;
            case RACE -> raceEdge;
            case SCRATCH -> scratchEdge;
            case BLACKJACK, BACCARAT, LOTTERY -> "";
        };
        if (own == null || own.isBlank()) {
            return houseEdge();
        }
        try {
            double read = Double.parseDouble(own.strip().replace("%", "").replace(',', '.'));
            return Double.isFinite(read) ? percent(read, 50) : houseEdge();
        } catch (NumberFormatException unreadable) {
            return houseEdge();
        }
    }

    /** An amount a game sets for itself; empty when it leaves it to the casino or it cannot be read. */
    private java.util.Optional<Money> ownMoney(String written) {
        if (written == null || written.isBlank()) {
            return java.util.Optional.empty();
        }
        if (written.strip().equals("0")) {
            return java.util.Optional.of(Money.ZERO);
        }
        return currency().parse(written);
    }

    public double baccaratCommission() {
        return percent(baccaratCommissionPercent, 50);
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

    public double lowestMultiplier() {
        return clamp(lowestMultiplier, 0.05, 1, 0.5);
    }

    public double highestMultiplier() {
        return clamp(highestMultiplier, 1, 10, 2.0);
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
