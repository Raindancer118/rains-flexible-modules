package de.raindancer.modules.jobs;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** What an owner decides about the job board. The goals themselves are in jobs.yml. */
@Settings(id = "jobs", topics = {
        @Topic(path = "jobs", title = "Job board", icon = Material.LECTERN),
})
public record JobsSettings(

        @In("jobs") @Title("Goals on the board at once") @Range(min = 1, max = 9)
        @Key("active-goals") int activeGoals,

        @In("jobs") @Title("Reward: percent of the median balance") @Range(min = 0, max = 1000)
        @Describe("A goal pays this much of the median balance of the players seen lately, shared by what "
                + "each gave. 100 is the median itself: a lot to a poor player, little to a rich one.")
        @Key("reward-percent") int rewardPercent,

        @In("jobs") @Title("Players counted for the median") @Range(min = 1, max = 365)
        @Describe("Only players seen in this many days, so the starting balance of everybody who tried the "
                + "server once and left does not set the reward.")
        @Key("median-of-days") int medianOfDays,

        @In("jobs") @Title("Smallest reward")
        @Describe("Whatever the median, a reached goal pays at least this.")
        @Key("least-reward") String leastReward,

        @In("jobs") @Title("Largest reward")
        @Describe("Empty for no limit.")
        @Key("most-reward") String mostReward,

        @In("jobs") @Title("Stop the shop selling what is collected")
        @Describe("While a goal collects cod, the shop sells no cod — otherwise cod bought from the shop could "
                + "be handed straight in for the reward.")
        @Key("stop-sales") boolean stopSales,

        @In("jobs") @Title("Tell everybody about goals")
        @Describe("A line in chat when a goal goes up, is reached or runs out.")
        @Key("announce") boolean announce,

        @In("jobs") @Title("Reward scale (percent)") @Range(min = 0, max = 1000)
        @Describe("Applied to every goal reward before it is paid and shown on the board. 100 leaves it as it "
                + "is; lower it to put less money into the economy, 0 pays nothing.")
        @Key("goals.pay-scale-percent") int payScalePercent) {

    public static final JobsSettings DEFAULTS = new JobsSettings(3, 100, 30, "100", "", true, true, 100);
}
