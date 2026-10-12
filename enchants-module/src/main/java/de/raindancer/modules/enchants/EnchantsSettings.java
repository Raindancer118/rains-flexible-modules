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
        int doubleJumpPowerPercent) {

    public static final EnchantsSettings DEFAULTS = new EnchantsSettings(30, 30, 8, 100);
}
