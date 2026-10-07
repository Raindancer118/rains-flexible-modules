package de.raindancer.modules.veintoggle;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** What an owner decides about the switch. The schema of the file and of the /settings page. */
@Settings(id = "veintoggle", topics = {
        @Topic(path = "veintoggle", title = "Vein mining", icon = Material.IRON_PICKAXE),
})
public record VeinToggleSettings(

        @In("veintoggle") @Title("On for somebody who never chose")
        @Describe("Whether Veinminer works for a player who has never typed /vein. Changing this "
                + "changes it for everybody who has not decided for themselves; anybody who has keeps "
                + "what they chose.")
        @Key("on-by-default")
        boolean onByDefault,

        @In("veintoggle") @Title("Say when a vein is held back")
        @Describe("Whether somebody who switched it off is reminded, in the action bar and at most "
                + "once every few seconds, that only the one block broke. Off, it is simply ordinary "
                + "mining.")
        @Key("say-when-held-back")
        boolean sayWhenHeldBack) {

    public static final VeinToggleSettings DEFAULTS = new VeinToggleSettings(true, true);
}
