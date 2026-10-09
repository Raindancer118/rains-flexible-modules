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
import org.bukkit.Material;

import java.util.List;

/**
 * Everything an owner decides about how much money there is: a hard cap and its treasury, the price index,
 * the stabiliser, and every brake on money coming in and every way for it to go out again.
 *
 * <p>Its own record because {@link EconomySettings} is near the most parameters a Java constructor may have.
 * Every default leaves the economy exactly as it was before these settings existed.
 */
@Settings(id = "economy-supply", topics = {
        @Topic(path = "supply", title = "Money supply", icon = Material.BEACON),
        @Topic(path = "supply/treasury", title = "Hard cap and treasury", icon = Material.IRON_BARS),
        @Topic(path = "supply/index", title = "Price index", icon = Material.COMPARATOR),
        @Topic(path = "supply/stabilizer", title = "Stabiliser", icon = Material.REPEATER),
        @Topic(path = "supply/earning", title = "Slowing money down", icon = Material.HOPPER),
        @Topic(path = "supply/tax", title = "Progressive taxes", icon = Material.PAPER),
        @Topic(path = "supply/sinks", title = "Things to spend on", icon = Material.ANVIL),
        @Topic(path = "supply/debts", title = "Debts", icon = Material.LEAD),
        @Topic(path = "supply/seasons", title = "Seasons", icon = Material.NETHER_STAR),
})
public record SupplySettings(

        // ------------------------------------------------------------------ hard cap and treasury
        @In("supply/treasury") @Title("Hard cap on money")
        @Describe("No money is printed past the cap below. Every payout — rewards, salary, interest, the shop "
                + "buying items, other plugins paying — comes out of the treasury, and every fee, tax, fine and "
                + "purchase goes back into it. The treasury is whatever of the cap nobody holds. When it is "
                + "empty, payouts are refused with a message until money comes back.")
        @Key("money-supply.capped") boolean capped,

        @In("supply/treasury") @Title("The cap")
        @Describe("The most money there may ever be: balances, cash, pots and escrow together. Set it above "
                + "what is out there now, or nothing is paid until enough has come back. /eco health shows how "
                + "much there is.")
        @Key("money-supply.cap") String cap,

        @In("supply/treasury") @Title("Pay less when the treasury runs low") @Range(min = 0, max = 100)
        @Describe("Percent of the cap. Below it, every payout shrinks in proportion to what is left: at half "
                + "of this, payouts are half. 0 pays in full until the treasury is empty.")
        @Key("money-supply.scale-below-percent") int scaleBelowPercent,

        @In("supply/treasury") @Title("/treasury for everybody")
        @Describe("Players can see how much money there is, what the treasury holds and where money came "
                + "from this week. Fees are easier to accept when people can see where they go.")
        @Key("features.treasury-info") boolean treasuryInfo,

        @In("supply/treasury") @Title("Check the cap every") @Range(min = 1, max = 1440)
        @Describe("Minutes. More money out there than the cap allows means a cap lowered under the money, or "
                + "money that came from somewhere it should not; staff with rainseconomy.alerts are told.")
        @Key("money-supply.audit-minutes") int auditMinutes,

        // ------------------------------------------------------------------ price index
        @In("supply/index") @Title("Basket of goods")
        @Describe("'<item> <how many>', comma separated. Priced at the shop once a day; how its price moves "
                + "is the server's inflation, shown in /eco health.")
        @Key("price-index.basket") List<String> basket,

        @In("supply/index") @Title("Fees follow prices")
        @Describe("Every fee any plugin charges through Core (teleports, upkeep, fines…) moves with the price "
                + "index, so a fee written a year ago costs as much in goods today.")
        @Key("price-index.fees-follow") boolean feesFollow,

        // ------------------------------------------------------------------ stabiliser
        @In("supply/stabilizer") @Title("Stabiliser")
        @Describe("Once a day, compares the last week's inflation with the target and turns money coming in "
                + "down (and fees up) when prices rise too fast — the other way round when they fall. Every "
                + "change is logged and staff are told.")
        @Key("features.stabilizer") boolean stabilizer,

        @In("supply/stabilizer") @Title("Target inflation a week") @Describe("Percent.")
        @Key("stabilizer.target-weekly-percent") double targetWeeklyPercent,

        @In("supply/stabilizer") @Title("Leave alone within") @Describe("Percent either side of the target.")
        @Key("stabilizer.tolerance-percent") double tolerancePercent,

        @In("supply/stabilizer") @Title("One day's turn") @Range(min = 1, max = 50)
        @Describe("Percent the taps move in one day.")
        @Key("stabilizer.step-percent") int stepPercent,

        @In("supply/stabilizer") @Title("Turned at most") @Range(min = 1, max = 90)
        @Describe("Percent either way, however long prices keep moving.")
        @Key("stabilizer.most-percent") int mostPercent,

        // ------------------------------------------------------------------ slowing money down
        @In("supply/earning") @Title("The same again pays less") @Range(min = 0, max = 90)
        @Describe("Percent less for each kill of the same mob, or block of the same ore, in the same chunk "
                + "within the window below. A mob farm reaches a ceiling; roaming does not. 0 is off.")
        @Key("earn.diminishing-percent") int diminishingPercent,

        @In("supply/earning") @Title("Remembered for") @Range(min = 1, max = 1440)
        @Describe("Minutes.")
        @Key("earn.diminishing-minutes") int diminishingMinutes,

        @In("supply/earning") @Title("Payouts shrink above, per player")
        @Describe("Money per active player. Above it, every payout shrinks in proportion: when everybody is "
                + "twice as rich as this, rewards are half. 0 is off.")
        @Key("earn.target-per-player") String targetPerPlayer,

        @In("supply/earning") @Title("Active means seen within") @Range(min = 1, max = 365)
        @Describe("Days.")
        @Key("earn.active-days") int activeDays,

        @In("supply/earning") @Title("Selling XP, at most a day")
        @Describe("What one player may be paid for experience in a day. 0 is no limit.")
        @Key("xp.sell-daily-most") String xpSellDailyMost,

        @In("supply/earning") @Title("The shop buys from one player at most")
        @Describe("A day. 0 is no limit.")
        @Key("shop.sell-budget-per-player") String sellBudgetPerPlayer,

        @In("supply/earning") @Title("The shop buys from everybody at most")
        @Describe("A day, all players together. 0 is no limit.")
        @Key("shop.sell-budget-server") String sellBudgetServer,

        @In("supply/earning") @Title("New accounts cannot send money for") @Range(min = 0, max = 720)
        @Describe("Hours after an account opens before it may /pay, bill or withdraw cash — farming money "
                + "on fresh accounts and handing it to a main one stops working. 0 is off.")
        @Key("pay.new-account-hours") int newAccountHours,

        // ------------------------------------------------------------------ progressive taxes
        @In("supply/tax") @Title("Payment tax by amount")
        @Describe("'<from> <percent>', comma separated: '0 1, 1000 3, 10000 8' — each part of a payment "
                + "is taxed at its own rate, like an income tax. Empty uses the flat payment tax.")
        @Key("pay.tax-brackets") List<String> payTaxBrackets,

        @In("supply/tax") @Title("Wealth tax by amount")
        @Describe("'<from> <percent>', comma separated, on the balance above the allowance. Empty uses the "
                + "flat wealth tax percent.")
        @Key("wealth-tax.brackets") List<String> wealthTaxBrackets,

        @In("supply/tax") @Title("Only tax money lying idle for") @Range(min = 0, max = 365)
        @Describe("Days. An account that sent or spent money within them is not taxed this time. 0 taxes "
                + "every balance.")
        @Key("wealth-tax.idle-days") int wealthTaxIdleDays,

        @In("supply/tax") @Title("Banknotes expire after") @Range(min = 0, max = 3650)
        @Describe("Days. A note paid in later is worth only the share below. Coins never expire. 0 is never.")
        @Key("cash.note-expiry-days") int noteExpiryDays,

        @In("supply/tax") @Title("An expired note is worth") @Range(min = 0, max = 100)
        @Describe("Percent of what is printed on it.")
        @Key("cash.expired-note-percent") int expiredNotePercent,

        // ------------------------------------------------------------------ things to spend on
        @In("supply/sinks") @Title("Dying costs") @Describe("Percent of the balance, destroyed. 0 is off.")
        @Key("death.lose-percent") double deathLosePercent,

        @In("supply/sinks") @Title("Dying costs at most") @Describe("0 is no limit.")
        @Key("death.lose-most") String deathLoseMost,

        @In("supply/sinks") @Title("Dying costs in")
        @Describe("World names, comma separated. Empty is every world.")
        @Key("death.worlds") List<String> deathWorlds,

        @In("supply/sinks") @Title("Jumping the auction queue costs")
        @Describe("A waiting auction can be moved to the front for this. 0 is off.")
        @Key("auction.jump-queue-price") String auctionJumpPrice,

        @In("supply/sinks") @Title("Community funds")
        @Describe("Staff start a fund with a goal (/eco fund); everybody donates with /fund, and when it is "
                + "full its reward happens — a boost to earnings for a while, or commands run. Donations are "
                + "destroyed.")
        @Key("features.funds") boolean funds,

        @In("supply/sinks") @Title("Funds in a boss bar")
        @Key("funds.boss-bar") boolean fundsBossBar,

        @In("supply/sinks") @Title("/repair")
        @Describe("Repairs the item in hand for money: the price below, scaled by how worn it is.")
        @Key("features.repair") boolean repair,

        @In("supply/sinks") @Title("A full repair costs")
        @Describe("Percent of what the item is worth in the shop.")
        @Key("repair.price-percent") double repairPercent,

        @In("supply/sinks") @Title("A repair costs at least")
        @Key("repair.least") String repairLeast,

        @In("supply/sinks") @Title("Repairing also clears the anvil's cost")
        @Describe("The 'too expensive' penalty an item collects each time it goes through an anvil.")
        @Key("repair.reset-anvil-cost") boolean repairResetsAnvil,

        // ------------------------------------------------------------------ debts
        @In("supply/debts") @Title("Of every payout, toward a debt") @Range(min = 0, max = 100)
        @Describe("Percent. Somebody who owes the server (an unpaid fine, missed upkeep) has this share of "
                + "every reward, salary, interest, sale and plugin payment taken toward it.")
        @Key("debts.share-of-income-percent") int debtSharePercent,

        @In("supply/debts") @Title("No gambling while in debt")
        @Key("debts.stop-gambling") boolean debtStopsGambling,

        // ------------------------------------------------------------------ seasons
        @In("supply/seasons") @Title("Seasons")
        @Describe("/eco season end turns every balance into season points (shown on /bank) on the scale "
                + "below and starts everybody again at the starting balance. Nothing happens on its own.")
        @Key("features.seasons") boolean seasons,

        @In("supply/seasons") @Title("Points per coin")
        @Describe("'<from> <points per coin>', comma separated: '0 1, 10000 0.5, 100000 0.1' — the richer, "
                + "the fewer points each further coin is worth.")
        @Key("season.points-brackets") List<String> seasonBrackets,

        @In("supply/seasons") @Title("Kept into the next season") @Range(min = 0, max = 100)
        @Describe("Percent of a balance that is not converted, on top of the starting balance.")
        @Key("season.keep-percent") int seasonKeepPercent) {

    public static final SupplySettings DEFAULTS = new SupplySettings(
            // treasury
            false, "0", 0, false, 10,
            // price index
            List.of("bread 16", "iron_ingot 16", "coal 16", "oak_log 32", "cobblestone 64", "wheat 32",
                    "diamond 2", "gold_ingot 4", "leather 8", "glass 16"),
            false,
            // stabiliser
            false, 1.0, 0.5, 5, 50,
            // earning
            0, 60, "0", 14, "0", "0", "0", 0,
            // taxes
            List.of(), List.of(), 0, 0, 50,
            // sinks
            0.0, "0", List.of(), "0", false, true, false, 10.0, "1", false,
            // debts
            50, true,
            // seasons
            false, List.of("0 1", "10000 0.5", "100000 0.1"), 0);

    /** An amount written here, in {@code currency}; unreadable is zero, which every setting here treats as off. */
    public static Money money(String written, Currency currency) {
        if (written == null || written.isBlank()) {
            return Money.ZERO;
        }
        return currency.parse(written.strip()).orElse(Money.ZERO);
    }
}
