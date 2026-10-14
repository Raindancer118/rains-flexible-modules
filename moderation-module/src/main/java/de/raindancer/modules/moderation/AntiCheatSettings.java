package de.raindancer.modules.moderation;

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
 * What an owner decides about the anti-cheat: what Grim's flags lead to, the client policy and the anti-ESP shield.
 * The checks themselves are Grim's and set in Grim's own config.
 */
@Settings(id = "anticheat", topics = {
        @Topic(path = "anticheat", title = "Anti-cheat", icon = Material.SHIELD),
        @Topic(path = "anticheat/actions", title = "What Grim's flags lead to", icon = Material.IRON_BARS),
        @Topic(path = "anticheat/checks", title = "Families of checks", icon = Material.COMPARATOR),
        @Topic(path = "anticheat/clients", title = "Clients", icon = Material.NAME_TAG),
        @Topic(path = "anticheat/reports", title = "Staff reports", icon = Material.WRITABLE_BOOK),
})
public record AntiCheatSettings(
        @In("anticheat") @Title("Taking Grim's flags in")
        @Describe("Off, Grim's flags are not taken in here and nothing is remembered. Grim itself keeps checking "
                + "and setting back. Players already flagged keep their record.")
        @Key("enabled")
        boolean enabled,

        @In("anticheat") @Title("Keep hidden players listed")
        @Describe("While the anti-ESP shield hides a player behind a wall, they stay in the tab list. Reads the "
                + "server's own connection; off, they drop off the tab list while hidden. Takes effect for players "
                + "who join after a change.")
        @Key("packet-tap")
        boolean packetTap,

        @In("anticheat/reports") @Title("Server lag that excuses a flag")
        @Describe("Flags raised while the server ran slower than this many ticks a second do not count towards a "
                + "staff report: a lagging server makes honest clients look odd.")
        @Key("min-tps")
        double minTps,

        // ─────────────────────────────────────────────────────────── actions

        @In("anticheat/actions") @Title("Tell staff")
        @Describe("Staff with rainsanticheat.alerts on every server of the network are told when Grim flags a "
                + "player past a family's alert level, at most once a second per family — repeats are counted "
                + "into the next line.")
        @Key("alerts")
        boolean alerts,

        @In("anticheat/actions") @Title("Kick")
        @Describe("A player whose level for a family reaches its kick level is kicked. The kick is recorded like "
                + "any other. Levels follow the player across the network.")
        @Key("auto-kick")
        boolean autoKick,

        @In("anticheat/actions") @Title("Ban")
        @Describe("A player whose level reaches a family's ban level is punished: by the server's rules (see "
                + "'Punish by the rules'), else banned for the ban length below. Off by default.")
        @Key("auto-ban")
        boolean autoBan,

        @In("anticheat/actions") @Title("Ban length")
        @Describe("How long an automatic ban lasts: 30m, 12h, 7d, or 'forever'.")
        @Key("ban-length")
        String banLength,

        @In("anticheat/actions") @Title("Punish by the rules")
        @Describe("With 'Ban' on, a player reaching a family's ban level gets what the server's rule about "
                + "cheating (/rules) says instead of the fixed ban, counted like a moderator hands it out: 1st "
                + "offence, 2nd, … At most once every five minutes per player. When that rule has no "
                + "punishments, the fixed ban is used.")
        @Key("punish-by-rules")
        boolean punishByRules,

        @In("anticheat/actions") @Title("Rule about cheating")
        @Describe("Which rule that is: its number in /rules, or 'auto' for the first rule whose title "
                + "mentions cheating or hacking.")
        @Key("cheating-rule")
        String cheatingRule,

        @In("anticheat/actions") @Title("Kick and ban levels, in percent") @Range(min = 10, max = 1000)
        @Describe("Scales every family's kick and ban level. 200 is twice as lenient, 50 twice as strict.")
        @Key("punish-scale")
        int punishScale,

        @In("anticheat/actions") @Title("Keep evidence")
        @Describe("Every flag is written down with Grim's check, its details and where it happened, for "
                + "/anticheat log and the player's page.")
        @Key("evidence")
        boolean evidence,

        @In("anticheat/actions") @Title("Evidence kept per player") @Range(min = 10, max = 5000)
        @Describe("The newest this many flags per player are kept; older ones fall off.")
        @Key("evidence-per-player")
        int evidencePerPlayer,

        // ─────────────────────────────────────────────────────────── checks

        @In("anticheat/checks") @Title("Switched off")
        @Describe("Families whose flags are not taken in: movement, combat, world, inventory, packets, client "
                + "(/anticheat checks lists them).")
        @Key("disabled-checks")
        List<String> disabledChecks,

        @In("anticheat/checks") @Title("Watch only")
        @Describe("Families that still alert and keep evidence but never kick or ban.")
        @Key("silent-checks")
        List<String> silentChecks,

        @In("anticheat/checks") @Title("Grim's experimental checks")
        @Describe("Whether the flags of checks Grim marks experimental count like the others.")
        @Key("experimental-checks")
        boolean experimentalChecks,

        // ─────────────────────────────────────────────────────────── clients

        @In("anticheat/clients") @Title("Refused clients")
        @Describe("Client brands a player may not join with, matched as part of the name, any case. "
                + "Brands are trivial to fake, so this only stops clients that announce themselves.")
        @Key("blocked-brands")
        List<String> blockedBrands,

        @In("anticheat/clients") @Title("Refused mod channels")
        @Describe("Plugin channels a client may not register, matched as part of the name.")
        @Key("blocked-channels")
        List<String> blockedChannels,

        @In("anticheat/clients") @Title("Kick refused clients")
        @Describe("On, a refused client is kicked. Off, staff are only told.")
        @Key("kick-blocked-clients")
        boolean kickBlockedClients,

        @In("anticheat/clients") @Title("Announce every client")
        @Describe("Tell staff which client brand every player joins with.")
        @Key("announce-brands")
        boolean announceBrands,

        @In("anticheat") @Title("Hide players behind walls")
        @Describe("Anti-ESP: a player nobody could see — solid blocks in every line between them — is not sent "
                + "to the other's client at all, so wallhacks and tracers have nothing to draw. They stay in the "
                + "tab list. Costs a few ray traces per pair of nearby players, five times a second. Not on Folia.")
        @Key("anti-esp")
        boolean antiEsp,

        @In("anticheat") @Title("Hide behind walls within") @Range(min = 16, max = 128)
        @Describe("Players further apart than this many blocks are left to the server's own tracking.")
        @Key("anti-esp-range")
        int antiEspRange,

        // ─────────────────────────────────────────────────────────── reports

        @In("anticheat/reports") @Title("Report cheaters")
        @Describe("When Grim keeps flagging a player over a stretch of time — not a burst, a pattern — one "
                + "report goes into the moderation queue, from 'the console', with what was seen and a link to "
                + "the evidence. It never punishes: staff decide. Not filed again while an open one about the "
                + "same player and kind waits.")
        @Key("auto-report")
        boolean autoReport,

        @In("anticheat/reports") @Title("Flags needed") @Range(min = 5, max = 500)
        @Describe("How many movement flags in the window; fights and building need fewer, as they flag only "
                + "while somebody fights or builds. Flags made at a bad ping or while the server lagged never count.")
        @Key("auto-report-flags")
        int autoReportFlags,

        @In("anticheat/reports") @Title("Window, in seconds") @Range(min = 30, max = 900)
        @Describe("How far back flags count towards a report. They must fall into at least three separate "
                + "five-second stretches of it.")
        @Key("auto-report-seconds")
        int autoReportSeconds,

        @In("anticheat/reports") @Title("Level still standing") @Range(min = 1, max = 200)
        @Describe("The family's level, after its decay, must still be this high: a hack that stopped a minute "
                + "ago has decayed away.")
        @Key("auto-report-level")
        int autoReportLevel,

        @In("anticheat/reports") @Title("Highest ping that counts") @Range(min = 50, max = 2000)
        @Describe("Flags made above this ping are put down to the connection and are not counted.")
        @Key("auto-report-max-ping")
        int autoReportMaxPing) {

    public static final AntiCheatSettings DEFAULTS = new AntiCheatSettings(
            true, true, 17.0,
            true, false, false, "7d", true, "auto", 100, true, 200,
            List.of(), List.of(), false,
            List.of("wurst", "meteor", "liquidbounce", "aristois", "rusherhack", "konas", "bleachhack",
                    "inertia", "novoline", "tenacity"),
            List.of("wurst", "meteor-client", "liquidbounce", "aristois", "bleachhack"),
            true, false, false, 64,
            true, 20, 120, 15, 300);

    public AntiCheatSettings withAutoReport(boolean on) {
        return new AntiCheatSettings(enabled, packetTap, minTps, alerts, autoKick, autoBan, banLength, punishByRules,
                cheatingRule, punishScale, evidence, evidencePerPlayer, disabledChecks, silentChecks, experimentalChecks,
                blockedBrands, blockedChannels, kickBlockedClients, announceBrands, antiEsp, antiEspRange, on,
                autoReportFlags, autoReportSeconds, autoReportLevel, autoReportMaxPing);
    }

    /** What the confidence rule needs, from the owner's numbers. */
    public de.raindancer.modules.moderation.rules.ConfidenceRule.Policy reportPolicy() {
        return new de.raindancer.modules.moderation.rules.ConfidenceRule.Policy(autoReportSeconds * 1000L, autoReportFlags,
                autoReportLevel, autoReportMaxPing, minTps, 3, 5_000);
    }

    public boolean disabled(String checkKey) {
        return contains(disabledChecks, checkKey);
    }

    public boolean silent(String checkKey) {
        return contains(silentChecks, checkKey);
    }

    private static boolean contains(List<String> list, String key) {
        if (list == null || key == null) {
            return false;
        }
        for (String entry : list) {
            if (entry != null && entry.trim().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** A listed fragment that this text contains, any case; null if none. */
    public static String matching(List<String> fragments, String text) {
        if (fragments == null || text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            if (fragment != null && !fragment.isBlank() && lower.contains(fragment.trim().toLowerCase(Locale.ROOT))) {
                return fragment.trim();
            }
        }
        return null;
    }
}
