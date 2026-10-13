package de.raindancer.modules.oneblock;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * Where the islands are and how long an invite waits. The same file on every server: the island server reads
 * {@code game-mode}, the others {@code island-server} and {@code proxy-server}.
 */
@Settings(id = "oneblock", topics = {
        @Topic(path = "oneblock", title = "OneBlock", icon = Material.GRASS_BLOCK),
})
public record OneBlockSettings(

        @In("oneblock") @Title("Island server's link")
        @Describe("On the other servers: the island server's name in link.yml (its key under peers:, or the "
                + "server-name it gives itself). Invites typed here are sent there.")
        @Key("island-server")
        String islandServer,

        @In("oneblock") @Title("Island server on the proxy")
        @Describe("The proxy's name for the island server — where /island and an accepted invite send people.")
        @Key("proxy-server")
        String proxyServer,

        @In("oneblock") @Title("An invite waits for") @Range(min = 1, max = 60)
        @Describe("Minutes an invite can be accepted. Past that it runs out and the inviter is told.")
        @Key("invite-minutes")
        int inviteMinutes,

        @In("oneblock") @Title("Game mode")
        @Describe("On the island server: the BentoBox game mode the islands belong to, by its addon name.")
        @Key("game-mode")
        String gameMode) {

    public static final OneBlockSettings DEFAULTS = new OneBlockSettings("oneblock", "oneblock", 5, "AOneBlock");

    public int inviteMinutesClamped() {
        return Math.clamp(inviteMinutes, 1, 60);
    }
}
