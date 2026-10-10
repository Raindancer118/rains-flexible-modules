package de.raindancer.modules.jobs;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** What an owner decides about orders — work players ask for by the amount they want. The work is in orders.yml. */
@Settings(id = "jobs-orders", topics = {
        @Topic(path = "orders", title = "Orders", icon = Material.CLOCK),
})
public record OrderSettings(

        @In("orders") @Title("Orders")
        @Describe("Players name an amount and get work to earn it in a set time: the more they ask, the harder the "
                + "work and the tighter the clock. /quests → Ask for work")
        @Key("enabled") boolean enabled,

        @In("orders") @Title("Orders a day") @Range(min = 1, max = 50)
        @Describe("How many orders one player may take a day, done or not.")
        @Key("per-day") int perDay,

        @In("orders") @Title("Other work offered, a day") @Range(min = 0, max = 50)
        @Describe("How often a player may turn an offer down for another, a day.")
        @Key("rerolls-per-day") int rerollsPerDay,

        @In("orders") @Title("Smallest amount")
        @Key("least") String least,

        @In("orders") @Title("Easy up to")
        @Describe("Up to this the work is easy and the clock generous.")
        @Key("easy-up-to") String easyUpTo,

        @In("orders") @Title("Hardest at")
        @Describe("At this the work is the hardest there is and the time a joke. Also the most anybody may ask for.")
        @Key("hardest-at") String hardestAt,

        @In("orders") @Title("Pace asked for at the easy end")
        @Describe("Of a skilled player's pace: 0.15 leaves plenty of time.")
        @Key("pace.easiest") double paceEasiest,

        @In("orders") @Title("Pace asked for at the hardest")
        @Describe("Of a skilled player's pace: 750 is seven hundred and fifty times faster than anybody can.")
        @Key("pace.hardest") double paceHardest) {

    public static final OrderSettings DEFAULTS = new OrderSettings(true, 3, 5, "100", "1000", "100000000000", 0.15, 750);
}
