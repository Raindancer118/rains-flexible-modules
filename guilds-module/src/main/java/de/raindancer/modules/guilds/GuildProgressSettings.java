package de.raindancer.modules.guilds;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * The further ways a guild earns experience, the together bonus and the weekly contracts. Its own record so the
 * first one keeps its shape; monsters, bosses, advancements and minutes stay in {@link GuildsSettings}.
 */
@Settings(id = "guilds-progress", topics = {
        @Topic(path = "guilds-progress", title = "Guild experience", icon = Material.EXPERIENCE_BOTTLE),
        @Topic(path = "guilds-progress/together", title = "Playing together", icon = Material.CAMPFIRE),
        @Topic(path = "guilds-progress/contracts", title = "Weekly contracts", icon = Material.FILLED_MAP),
        @Topic(path = "guilds-progress/honours", title = "Perks, prestige and the crown", icon = Material.NETHER_STAR),
})
public record GuildProgressSettings(

        @In("guilds-progress") @Title("Per ore mined") @Range(min = 0, max = 10_000)
        @Describe("Times how rare the ore is: coal and copper 1, iron 2, gold 3, diamond 8, emerald 10, ancient "
                + "debris 15. Only ores the world made — never one a player placed.")
        @Key("xp.ore") int perOre,

        @In("guilds-progress") @Title("Per fish caught") @Range(min = 0, max = 10_000)
        @Key("xp.fish") int perFish,

        @In("guilds-progress") @Title("Per animal bred") @Range(min = 0, max = 10_000)
        @Key("xp.breed") int perBreed,

        @In("guilds-progress") @Title("Per villager trade") @Range(min = 0, max = 10_000)
        @Key("xp.trade") int perTrade,

        @In("guilds-progress") @Title("Per crop harvested") @Range(min = 0, max = 10_000)
        @Describe("Fully grown crops, nether wart and cocoa, and melons and pumpkins the world grew.")
        @Key("xp.harvest") int perHarvest,

        @In("guilds-progress") @Title("Per enchanting level spent") @Range(min = 0, max = 10_000)
        @Describe("The levels an enchantment actually takes (1 to 3), not the level it asks to be at.")
        @Key("xp.enchant-level") int perEnchantLevel,

        @In("guilds-progress") @Title("Per raid won") @Range(min = 0, max = 100_000)
        @Describe("Each guild member among the raid's heroes.")
        @Key("xp.raid") int perRaid,

        @In("guilds-progress") @Title("Per rival defeated") @Range(min = 0, max = 100_000)
        @Describe("Killing a member of a guild either side counts as a rival. The same victim counts once an hour.")
        @Key("xp.rival-kill") int perRivalKill,

        @In("guilds-progress") @Title("Per 1000 paid into the bank") @Range(min = 0, max = 100_000)
        @Describe("0 keeps levels from being bought. Money taken out again does not take it back.")
        @Key("xp.deposit-per-thousand") int perThousandDeposited,

        @In("guilds-progress/together") @Title("Bonus per guildmate nearby (percent)") @Range(min = 0, max = 100)
        @Key("together.percent-per-member") int togetherPercent,

        @In("guilds-progress/together") @Title("Most bonus (percent)") @Range(min = 0, max = 500)
        @Key("together.most-percent") int togetherMost,

        @In("guilds-progress/together") @Title("Nearby means within (blocks)") @Range(min = 1, max = 512)
        @Key("together.radius") int togetherRadius,

        @In("guilds-progress/contracts") @Title("Weekly contracts")
        @Key("contracts.enabled") boolean contracts,

        @In("guilds-progress/contracts") @Title("Contracts a week") @Range(min = 1, max = 9)
        @Key("contracts.per-week") int contractsPerWeek,

        @In("guilds-progress/contracts") @Title("Reward (percent of the guild's next level)") @Range(min = 1, max = 500)
        @Describe("Each contract pays this much of the experience between the guild's level and the next, so "
                + "contracts stay worth doing at every level.")
        @Key("contracts.reward-percent") int contractRewardPercent,

        @In("guilds-progress/contracts") @Title("Size (percent)") @Range(min = 10, max = 1000)
        @Describe("Scales how much each contract asks for. Contracts already grow with the guild's member count.")
        @Key("contracts.size-percent") int contractSizePercent,

        @In("guilds-progress/honours") @Title("Perk points per prestige") @Range(min = 0, max = 100)
        @Describe("On top of the point every level up brings — which a prestige keeps.")
        @Key("honours.points-per-prestige") int pointsPerPrestige,

        @In("guilds-progress/honours") @Title("Perk points per weekly crown") @Range(min = 0, max = 100)
        @Key("honours.points-per-crown") int pointsPerCrown,

        @In("guilds-progress/honours") @Title("Days between taking perk points back") @Range(min = 0, max = 365)
        @Key("honours.respec-days") int respecDays,

        @In("guilds-progress/honours") @Title("Guild experience bonus per prestige (percent)") @Range(min = 0, max = 100)
        @Key("honours.percent-per-prestige") int percentPerPrestige) {

    public static final GuildProgressSettings DEFAULTS = new GuildProgressSettings(
            1, 3, 2, 2, 1, 5, 200, 50, 0,
            10, 50, 48,
            true, 3, 25, 100,
            2, 1, 7, 5);
}
