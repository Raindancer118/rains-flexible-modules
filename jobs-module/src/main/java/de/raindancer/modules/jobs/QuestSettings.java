package de.raindancer.modules.jobs;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** What an owner decides about personal quests. The quests themselves are in quests.yml. */
@Settings(id = "jobs-quests", topics = {
        @Topic(path = "quests", title = "Personal quests", icon = Material.WRITABLE_BOOK),
})
public record QuestSettings(

        @In("quests") @Title("Personal quests")
        @Describe("Every player gets a few quests a day — mining, fishing, hunting… — and is paid as soon as one "
                + "is done. /quests")
        @Key("quests.enabled") boolean enabled,

        @In("quests") @Title("Quests for anybody a day") @Range(min = 0, max = 9)
        @Key("quests.per-day") int perDay,

        @In("quests") @Title("Quests for the player's role a day") @Range(min = 0, max = 9)
        @Describe("Extra quests for whoever has a role (/role): a Miner's are about ore, a Hunter's about monsters.")
        @Key("quests.per-day-for-role") int perDayForRole,

        @In("quests") @Title("Wealth step")
        @Describe("A player with this much is tier 1; every doubling past it is a tier more — three steps is tier 2, "
                + "seven is tier 3. Empty or 0: everybody is tier 0.")
        @Key("quests.tiers.step") String wealthStep,

        @In("quests") @Title("Each tier asks more (percent)") @Range(min = 0, max = 200)
        @Key("quests.tiers.harder-percent") int harderPercent,

        @In("quests") @Title("Each tier pays more (percent)") @Range(min = 0, max = 300)
        @Describe("Keep it above what each tier asks more, so a rich player's quests are still worth doing.")
        @Key("quests.tiers.pay-more-percent") int payMorePercent,

        @In("quests") @Title("Highest tier") @Range(min = 0, max = 20)
        @Key("quests.tiers.most") int mostTier,

        @In("quests") @Title("A role's own quests pay more (percent)") @Range(min = 0, max = 200)
        @Key("quests.role-bonus-percent") int roleBonusPercent,

        @In("quests") @Title("Pay scale (percent)") @Range(min = 0, max = 1000)
        @Describe("Applied to every quest's pay. 100 leaves it; lower it to put less money into the economy.")
        @Key("quests.pay-scale-percent") int payScalePercent,

        @In("quests") @Title("Tell players about the day's quests")
        @Describe("A line when somebody joins and has new quests.")
        @Key("quests.tell-on-join") boolean tellOnJoin,

        @In("quests") @Title("Free swaps a day") @Range(min = 0, max = 9)
        @Describe("How many of the day's quests a player may swap for another of the same kind, free — one not "
                + "begun yet. Clicking a quest in /quests offers it.")
        @Key("quests.free-rerolls") int freeRerolls) {

    public static final QuestSettings DEFAULTS = new QuestSettings(true, 3, 2, "5000", 35, 60, 8, 25, 100, true, 1);
}
