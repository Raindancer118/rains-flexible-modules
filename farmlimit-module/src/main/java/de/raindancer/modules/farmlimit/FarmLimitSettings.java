package de.raindancer.modules.farmlimit;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** How big an animal farm may grow. The schema of the file and of the /settings page. */
@Settings(id = "farmlimit", topics = {
        @Topic(path = "farmlimit", title = "Farm limits", icon = Material.EGG),
})
public record FarmLimitSettings(

        @In("farmlimit") @Title("Limit farms")
        @Describe("Whether breeding, thrown eggs and dispensed eggs stop making new animals where "
                + "there are already too many. Animals that exist are never removed.")
        @Key("enabled")
        boolean enabled,

        @In("farmlimit") @Title("Counted within") @Range(min = 4, max = 64)
        @Describe("Blocks around a new animal in which the others are counted. A farm across a chunk "
                + "border is still one farm.")
        @Key("radius")
        int radius,

        @In("farmlimit") @Title("Most of one kind") @Range(min = 0, max = 1000)
        @Describe("How many animals of the same kind may be that close before no more are bred or "
                + "hatched. Zero means no limit.")
        @Key("most-of-one-kind")
        int mostOfOneKind,

        @In("farmlimit") @Title("Most animals in all") @Range(min = 0, max = 2000)
        @Describe("How many breedable animals of any kind may be that close together. Zero means no limit.")
        @Key("most-animals")
        int mostAnimals,

        @In("farmlimit") @Title("Tell players nearby")
        @Describe("Whether players near a farm that is full are told, in the action bar and at most "
                + "once a minute, why nothing more is born.")
        @Key("tell-players")
        boolean tellPlayers) {

    public static final FarmLimitSettings DEFAULTS = new FarmLimitSettings(true, 16, 50, 150, true);
}
