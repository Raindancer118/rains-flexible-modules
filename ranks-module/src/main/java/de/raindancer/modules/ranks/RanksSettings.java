package de.raindancer.modules.ranks;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** Where ranks show, for the whole server. The ranks themselves are in ranks.yml. */
@Settings(id = "ranks", topics = {
        @Topic(path = "ranks", title = "Ranks", icon = Material.GOLDEN_HELMET,
                description = "[OWNER], [DEV], [ADMIN] in front of a name — and where."),
})
public record RanksSettings(

        @In("ranks") @Title("Show ranks in chat")
        @Describe("The rank in front of the name on every chat line. Off, nobody's shows there, whatever they chose.")
        @Key("show.chat") boolean chat,

        @In("ranks") @Title("Show ranks above heads")
        @Describe("The rank in front of the name floating above a player. Needs Core's styled nametags "
                + "(the cosmetics module switches them on).")
        @Key("show.nametag") boolean nametag,

        @In("ranks") @Title("Show ranks in the tablist")
        @Describe("The rank in front of the name in the player list.")
        @Key("show.tablist") boolean tablist,

        @In("ranks") @Title("Players choose for themselves")
        @Describe("Lets everybody with a rank switch it off in chat, above their head or in the tablist, "
                + "and pick which of their ranks they wear. Off, every rank shows wherever the server shows them.")
        @Key("players-choose") boolean playersChoose,

        @In("ranks") @Title("Sort the tablist by rank")
        @Describe("Puts more senior ranks higher in the player list. Needs Core's 'Sort by rank' on too.")
        @Key("sort-tablist") boolean sortTablist) {

    public static final RanksSettings DEFAULTS = new RanksSettings(true, true, true, true, true);
}
