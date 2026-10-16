package de.raindancer.modules.oneblock;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * Where the islands are, how long an invite waits, and how the island server keeps its worlds. The same file on every
 * server: the island server reads the island part, the others {@code island-server} and {@code proxy-server}.
 */
@Settings(id = "oneblock", topics = {
        @Topic(path = "oneblock", title = "OneBlock", icon = Material.GRASS_BLOCK),
        @Topic(path = "oneblock/islands", title = "Island server", icon = Material.GRASS_BLOCK),
})
public record OneBlockSettings(

        @In("oneblock") @Title("Island server's link")
        @Describe("On the other servers: the island server's name in link.yml (its key under peers:, or the "
                + "server-name it gives itself). Invites typed here are sent there. Not used on a network, where "
                + "'Island server on the proxy' is also the island server's id.")
        @Key("island-server")
        String islandServer,

        @In("oneblock") @Title("Island server on the proxy")
        @Describe("The proxy's name for the island server — where /island and an accepted invite send people. "
                + "On a network it must be the island server's id in network.yml: the server with that id is the "
                + "island server, without anything else to set.")
        @Key("proxy-server")
        String proxyServer,

        @In("oneblock") @Title("An invite waits for") @Range(min = 1, max = 60)
        @Describe("Minutes an invite can be accepted. Past that it runs out and the inviter is told.")
        @Key("invite-minutes")
        int inviteMinutes,

        @In("oneblock/islands") @Title("This is the island server")
        @Describe("Keeps the islands on this server. Only needed off a network — on one, the server whose id is "
                + "'Island server on the proxy' is the island server by itself.")
        @Key("hosts-islands")
        boolean hostsIslands,

        @In("oneblock/islands") @Title("Unload an empty island after") @Range(min = 10, max = 3600)
        @Describe("Seconds an island's worlds stay loaded with nobody on them. An admin can keep one up or down "
                + "with /islandadmin, and switch this off for one island.")
        @Key("unload-seconds")
        int unloadSeconds,

        @In("oneblock/islands") @Title("SMP money commands")
        @Describe("Commands that move or show SMP money. On the island server they only work for players whose "
                + "island plays with the SMP economy; everybody else is told their island does not.")
        @Key("smp-money-commands")
        List<String> smpMoneyCommands,

        @In("oneblock/islands") @Title("Waiting world")
        @Describe("Where somebody without an island waits, and where people go when an admin takes an island "
                + "down under them. Empty for this server's main world.")
        @Key("waiting-world")
        String waitingWorld) {

    public static final List<String> MONEY_COMMANDS = List.of("shop", "sell", "pay", "balance", "bal", "money",
            "baltop", "bank", "withdraw", "deposit", "daily", "bill", "loan", "auction", "ah", "casino", "coinflip",
            "cf", "dice", "slots", "lottery", "claimadvancements", "backpay");

    public static final OneBlockSettings DEFAULTS = new OneBlockSettings("oneblock", "oneblock", 5, false, 60,
            MONEY_COMMANDS, "");

    public OneBlockSettings {
        smpMoneyCommands = smpMoneyCommands == null ? List.of()
                : smpMoneyCommands.stream().map(command -> command.strip().toLowerCase(Locale.ROOT))
                .map(command -> command.startsWith("/") ? command.substring(1) : command)
                .filter(command -> !command.isEmpty()).toList();
        waitingWorld = waitingWorld == null ? "" : waitingWorld.strip();
    }

    public int inviteMinutesClamped() {
        return Math.clamp(inviteMinutes, 1, 60);
    }

    public long unloadMillis() {
        return Math.clamp(unloadSeconds, 10, 3600) * 1000L;
    }

    /** Whether a typed command — its first word, with or without a plugin prefix — moves or shows SMP money. */
    public boolean isMoneyCommand(String typed) {
        if (typed == null) {
            return false;
        }
        String first = typed.strip();
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        int space = first.indexOf(' ');
        String label = (space < 0 ? first : first.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return smpMoneyCommands.contains(colon < 0 ? label : label.substring(colon + 1));
    }
}
