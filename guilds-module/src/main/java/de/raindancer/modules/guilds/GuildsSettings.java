package de.raindancer.modules.guilds;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.guilds.rules.ShareRule;
import org.bukkit.Material;

import java.util.List;

/** What an owner decides about guilds. Each guild's own choices are in guilds.db, made in /guild. */
@Settings(id = "guilds", topics = {
        @Topic(path = "guilds", title = "Guilds", icon = Material.WHITE_BANNER),
        @Topic(path = "guilds/levels", title = "Levels and experience", icon = Material.EXPERIENCE_BOTTLE),
        @Topic(path = "guilds/bank", title = "Bank", icon = Material.GOLD_INGOT),
        @Topic(path = "guilds/home", title = "Guild home", icon = Material.RED_BED),
})
public record GuildsSettings(

        @In("guilds") @Title("Price to found a guild")
        @Describe("Paid by the founder. 0 founds for free. Needs an economy when it is not 0.")
        @Key("founding-price") String foundingPrice,

        @In("guilds") @Title("Shortest name") @Range(min = 1, max = 32)
        @Key("name.shortest") int shortestName,

        @In("guilds") @Title("Longest name") @Range(min = 3, max = 48)
        @Key("name.longest") int longestName,

        @In("guilds") @Title("Shortest tag") @Range(min = 1, max = 8)
        @Key("tag.shortest") int shortestTag,

        @In("guilds") @Title("Longest tag") @Range(min = 1, max = 8)
        @Describe("The tag goes in front of every member's name in chat and the tablist, so keep it short.")
        @Key("tag.longest") int longestTag,

        @In("guilds") @Title("Words no name or tag may contain")
        @Describe("Matched ignoring case, accents and digits standing in for letters.")
        @Key("name.banned-words") List<String> bannedWords,

        @In("guilds") @Title("Most ranks a guild may have") @Range(min = 2, max = 20)
        @Key("most-ranks") int mostRanks,

        @In("guilds") @Title("Invitations last (hours)") @Range(min = 1, max = 720)
        @Key("invitation-hours") int invitationHours,

        @In("guilds") @Title("Applications last (days)") @Range(min = 1, max = 60)
        @Key("application-days") int applicationDays,

        @In("guilds") @Title("A leader unseen this many days is replaced") @Range(min = 0, max = 365)
        @Describe("The most senior member who has played within the same time leads instead. 0 never replaces a leader.")
        @Key("succession-days") int successionDays,

        @In("guilds") @Title("Tag in front of names")
        @Describe("Shows each member's guild tag, in the guild's colour, before their name in chat and the tablist.")
        @Key("tag-in-names") boolean tagInNames,

        @In("guilds") @Title("Tell everybody when a guild is founded or disbanded")
        @Key("announce") boolean announce,

        @In("guilds/levels") @Title("Experience for level 2") @Range(min = 1, max = 10_000_000)
        @Describe("Level n needs this × (n−1)^growth experience in total.")
        @Key("levels.base") long levelBase,

        @In("guilds/levels") @Title("Growth") @Range(min = 1, max = 4)
        @Describe("1 makes every level cost the same; higher makes later levels steeper.")
        @Key("levels.growth") double levelGrowth,

        @In("guilds/levels") @Title("Top level") @Range(min = 1, max = 100)
        @Key("levels.top") int topLevel,

        @In("guilds/levels") @Title("Members at level 1") @Range(min = 1, max = 500)
        @Key("levels.members-at-first") int membersAtFirst,

        @In("guilds/levels") @Title("More members per level") @Range(min = 0, max = 100)
        @Key("levels.members-per-level") int membersPerLevel,

        @In("guilds/levels") @Title("Most members, whatever the level") @Range(min = 0, max = 1000)
        @Describe("0 for no limit beyond what the levels give.")
        @Key("levels.most-members") int mostMembers,

        @In("guilds/levels") @Title("Allies at level 1") @Range(min = 0, max = 50)
        @Key("levels.allies-at-first") int alliesAtFirst,

        @In("guilds/levels") @Title("Levels per further ally") @Range(min = 1, max = 100)
        @Key("levels.levels-per-ally") int levelsPerAlly,

        @In("guilds/levels") @Title("Guild home from level") @Range(min = 1, max = 100)
        @Key("levels.home-from") int homeFromLevel,

        @In("guilds/levels") @Title("Most experience a member earns an hour") @Range(min = 0, max = 1_000_000)
        @Describe("So one player in a mob farm cannot carry a guild alone. 0 for no limit.")
        @Key("xp.hourly-cap") long hourlyCap,

        @In("guilds/levels") @Title("Size damping") @Range(min = 0, max = 1)
        @Describe("How much less each member of a big guild earns it. 0 is none.")
        @Key("xp.size-damping") double sizeDamping,

        @In("guilds/levels") @Title("Experience per monster killed") @Range(min = 0, max = 10_000)
        @Key("xp.monster") int xpPerMonster,

        @In("guilds/levels") @Title("Experience per boss killed") @Range(min = 0, max = 100_000)
        @Describe("The wither, the ender dragon, an elder guardian or a warden.")
        @Key("xp.boss") int xpPerBoss,

        @In("guilds/levels") @Title("Experience per advancement") @Range(min = 0, max = 10_000)
        @Describe("Only advancements announced in chat — not recipes.")
        @Key("xp.advancement") int xpPerAdvancement,

        @In("guilds/levels") @Title("Experience per minute played") @Range(min = 0, max = 1000)
        @Describe("Only minutes they were not away.")
        @Key("xp.minute") int xpPerMinute,

        @In("guilds/bank") @Title("Guild banks")
        @Key("bank.enabled") boolean bankEnabled,

        @In("guilds/bank") @Title("Most a bank may hold")
        @Describe("Empty for no limit.")
        @Key("bank.most") String bankMost,

        @In("guilds/bank") @Title("When a guild is disbanded, its bank goes")
        @Describe("leader: all to the leader. contribution: shared by what each member put in. even: shared evenly.")
        @Key("bank.disband-share") ShareRule.Mode disbandShare,

        @In("guilds/bank") @Title("Daily limit for officers")
        @Describe("What a new guild's officers may take out a day. Each guild changes its own afterwards.")
        @Key("bank.officer-limit") String officerLimit,

        @In("guilds/bank") @Title("Daily limit for members")
        @Key("bank.member-limit") String memberLimit,

        @In("guilds/home") @Title("Warm-up (seconds)") @Range(min = 0, max = 60)
        @Key("home.warmup") int homeWarmup,

        @In("guilds/home") @Title("Wait between trips (seconds)") @Range(min = 0, max = 86_400)
        @Key("home.cooldown") int homeCooldown,

        @In("guilds/levels") @Title("Colours for tag and name from level") @Range(min = 1, max = 100)
        @Describe("From this guild level on, a guild paints its tag and its name: a colour, a gradient, bold. "
                + "Below it, they wear the guild's plain colour.")
        @Key("levels.style-from") int styleFromLevel,

        @In("guilds") @Title("Guild in the sidebar")
        @Describe("Under the balance in the sidebar: your guild, its level and the experience to the next, and its bank.")
        @Key("sidebar") boolean sidebar,

        @In("guilds") @Title("Shards to unlock an enchantment") @Range(min = 1, max = 100_000)
        @Describe("How many shards of one of Rain's enchantments the members pay in together before the guild "
                + "has it: from then on every member can buy its books at the shop.")
        @Key("enchants.unlock-shards") int unlockShards) {

    public static final GuildsSettings DEFAULTS = new GuildsSettings(
            "0", 3, 24, 2, 5, List.of(), 8, 48, 7, 30, true, true,
            1000, 1.6, 25, 8, 2, 50, 1, 5, 3, 600, 0.35, 2, 250, 25, 1,
            true, "", ShareRule.Mode.CONTRIBUTION, "1000", "100",
            5, 60, 2, true, 100);
}
