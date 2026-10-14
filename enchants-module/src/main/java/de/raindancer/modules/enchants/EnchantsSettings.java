package de.raindancer.modules.enchants;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** What an owner decides about the enchantments. The schema of the file and of the /settings page. */
@Settings(id = "enchants", topics = {
        @Topic(path = "enchants", title = "Enchantments", icon = Material.ENCHANTED_BOOK),
        @Topic(path = "enchants/shards", title = "Shards and the shard world", icon = Material.ECHO_SHARD),
})
public record EnchantsSettings(

        @In("enchants") @Title("Flight seconds per level") @Range(min = 1, max = 3600)
        @Describe("How many seconds of flight each level of the Flight enchantment holds: level III at 30 "
                + "is a minute and a half in the air per charge.")
        @Key("flight-seconds-per-level")
        int flightSecondsPerLevel,

        @In("enchants") @Title("Seconds to recharge from empty") @Range(min = 1, max = 3600)
        @Describe("How long standing on the ground takes to refill an empty Flight charge. Flying, "
                + "or hanging in the air, does not recharge it.")
        @Key("flight-recharge-seconds")
        int flightRechargeSeconds,

        @In("enchants") @Title("Soft landing when flight ends") @Range(min = 0, max = 60)
        @Describe("Seconds of Slow Falling given when Flight runs out or is taken off in mid-air, so "
                + "running dry is not a death sentence. Zero gives none.")
        @Key("landing-grace-seconds")
        int landingGraceSeconds,

        @In("enchants") @Title("Double Jump power, percent") @Range(min = 10, max = 300)
        @Describe("How hard Double Jump pushes: 100 is a little over two blocks of extra height.")
        @Key("double-jump-power-percent")
        int doubleJumpPowerPercent,

        @In("enchants") @Title("Seconds for a Boost charge to come back") @Range(min = 5, max = 3600)
        @Describe("An elytra with Boost holds one charge per level; each spent one comes back after this long, "
                + "one at a time, flying or not.")
        @Key("boost-recharge-seconds")
        int boostRechargeSeconds,

        @In("enchants") @Title("Boost speed, percent of a rocket") @Range(min = 50, max = 1000)
        @Describe("How fast a Boost pulls: 100 is a firework rocket, 150 half as fast again. Far above 400 the "
                + "ground ahead may not be loaded in time, and an anti-cheat may take the speed for a cheat.")
        @Key("boost-speed-percent")
        int boostSpeedPercent,

        @In("enchants") @Title("Launch speed, metres per second") @Range(min = 20, max = 300)
        @Describe("How fast Launch throws the player forward once the elytra opens. The air slows a glider "
                + "down quickly, and the faster this is the more chunks the server has to load at once.")
        @Key("launch-speed")
        int launchSpeed,

        @In("enchants") @Title("Launch cooldown, seconds") @Range(min = 10, max = 86400)
        @Describe("How long Launch I waits between launches; level II waits two thirds of it, III a third.")
        @Key("launch-cooldown-seconds")
        int launchCooldownSeconds,

        @In("enchants") @Title("Wisdom, extra experience per level, percent") @Range(min = 1, max = 500)
        @Describe("How much more experience mobs and blocks give per level of Wisdom: 50 makes Wisdom III "
                + "two and a half times as much.")
        @Key("wisdom-percent-per-level")
        int wisdomPercentPerLevel,

        @In("enchants/shards") @Title("The shard world")
        @Describe("An amplified world in eternal night, full of monsters, where the enchantments' shards drop. "
                + "Created at the first start if it does not exist (not on Folia, which cannot make worlds while "
                + "running: create it there beforehand).")
        @Key("shard-world-enabled")
        boolean shardWorldEnabled,

        @In("enchants/shards") @Title("Shard world name")
        @Key("shard-world")
        String shardWorld,

        @In("enchants/shards") @Title("Shard world radius") @Range(min = 200, max = 30000)
        @Describe("Its world border, in blocks from the middle. Amplified ground is slow to generate: keep it small "
                + "or generate it beforehand.")
        @Key("shard-world-radius")
        int shardWorldRadius,

        @In("enchants/shards") @Title("Monsters at once") @Range(min = 10, max = 500)
        @Describe("How many monsters the shard world keeps around its players; the game's own is 70.")
        @Key("shard-world-monsters")
        int shardWorldMonsters,

        @In("enchants/shards") @Title("Portal key")
        @Describe("Right-click the inside of an obsidian portal frame with this to open it to the shard world; "
                + "one is used up. Any portal in the shard world leads back to where the player came from.")
        @Key("portal-key")
        Material portalKey,

        @In("enchants/shards") @Title("Shard chance, percent") @Range(min = 1, max = 100)
        @Describe("How likely a creature of the shard world — any kind, monster or animal — drops its shards when a "
                + "player kills it, as many as its tier: a Shadowed one 1, Shardborn 2, Crystal 3-4, a Shard Tyrant "
                + "6-8. Only creatures that came by themselves: bred, spawner, egg and command ones never do.")
        @Key("shard-drop-percent")
        int shardDropPercent,

        @In("enchants/shards") @Title("Shards per player per hour") @Range(min = 0, max = 10000)
        @Describe("The most shards one player can find in any hour, so a farm in the eternal night cannot print them. 0 for no limit.")
        @Key("shards-per-hour")
        int shardsPerHour,

        @In("enchants/shards") @Title("Thunderstorms, percent of the time") @Range(min = 0, max = 100)
        @Describe("How much of the time the shard world lies under a thunderstorm; rolled anew every five minutes.")
        @Key("shard-world-thunder-percent")
        int shardWorldThunderPercent,

        @In("enchants/shards") @Title("Haze specks") @Range(min = 0, max = 100)
        @Describe("Dark red specks drifting around each player in the shard world, every half second; 0 for none.")
        @Key("shard-world-haze")
        int shardWorldHaze) {

    public static final EnchantsSettings DEFAULTS = new EnchantsSettings(30, 30, 8, 100, 90, 150, 200, 600, 50,
            true, "shardlands", 2500, 140, Material.AMETHYST_SHARD, 100, 150, 70, 12);
}
