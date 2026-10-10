package de.raindancer.modules.anticheat;

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

/** What an owner decides about the anti-cheat. The schema of the file and of the /settings page. */
@Settings(id = "anticheat", topics = {
        @Topic(path = "anticheat", title = "Anti-cheat", icon = Material.SHIELD),
        @Topic(path = "anticheat/actions", title = "What a failed check does", icon = Material.IRON_BARS),
        @Topic(path = "anticheat/checks", title = "Checks", icon = Material.COMPARATOR),
        @Topic(path = "anticheat/clients", title = "Clients", icon = Material.NAME_TAG),
})
public record AntiCheatSettings(

        @In("anticheat") @Title("Checking")
        @Describe("Off, nothing is checked and nothing is remembered. Players already flagged keep "
                + "their record.")
        @Key("enabled")
        boolean enabled,

        @In("anticheat") @Title("Read packets")
        @Describe("Lets the anti-cheat see the client's own packets: every movement (also hovering, which "
                + "the server's move event never reports), the client's tick clock for the timer check, "
                + "and the order of attacks and swings. Off, only server events are used and the timer, "
                + "post and packet checks do not run. Takes effect for players who join after a change.")
        @Key("packet-tap")
        boolean packetTap,

        @In("anticheat") @Title("Pause below this TPS")
        @Describe("While the server runs slower than this, movement, timer and break-speed checks pause: a "
                + "lagging server makes honest clients look fast.")
        @Key("min-tps")
        double minTps,

        @In("anticheat") @Title("Most lag to make up for") @Range(min = 50, max = 5000)
        @Describe("Lag compensation grows with a player's ping up to this many milliseconds and no "
                + "further. Nobody is exempt for a bad ping: a client can fake any ping it likes.")
        @Key("max-ping")
        int maxPing,

        @In("anticheat") @Title("Leave Bedrock players alone")
        @Describe("Players joining through Geyser/Floodgate move by Bedrock's physics, which none of these "
                + "checks model. On, they are not checked.")
        @Key("exempt-bedrock")
        boolean exemptBedrock,

        @In("anticheat") @Title("Leave translated clients alone")
        @Describe("Players joining with another game version through ViaVersion send their moves translated, "
                + "by that version's physics and packet order. On, movement and packet checks leave them "
                + "alone; combat, world and inventory checks still run. Off by default: a cheat client can "
                + "simply pick an old version to slip past those checks. /anticheat info shows their version.")
        @Key("exempt-translated")
        boolean exemptTranslated,

        // ─────────────────────────────────────────────────────────── actions

        @In("anticheat/actions") @Title("Tell staff")
        @Describe("Staff with rainsanticheat.alerts are told when a player fails a check, at most once a "
                + "second per check — repeated failures are counted into the next line.")
        @Key("alerts")
        boolean alerts,

        @In("anticheat/actions") @Title("Set back")
        @Describe("Movement that fails a check is undone: the player is put back where they last moved "
                + "legitimately.")
        @Key("setbacks")
        boolean setbacks,

        @In("anticheat/actions") @Title("Cancel")
        @Describe("A hit, block break or placement that fails a check does not happen.")
        @Key("cancel")
        boolean cancel,

        @In("anticheat/actions") @Title("Kick")
        @Describe("A player whose violation level for a check reaches its kick level is kicked. The kick "
                + "is recorded like any other.")
        @Key("auto-kick")
        boolean autoKick,

        @In("anticheat/actions") @Title("Ban")
        @Describe("A player whose violation level reaches a check's ban level is punished: by the server's "
                + "rules (see 'Punish by the rules'), else banned for the ban length below. Off by default: only "
                + "checks that cannot fail by accident have a ban level at all, and nothing happens there while off.")
        @Key("auto-ban")
        boolean autoBan,

        @In("anticheat/actions") @Title("Ban length")
        @Describe("How long an automatic ban lasts: 30m, 12h, 7d, or 'forever'.")
        @Key("ban-length")
        String banLength,

        @In("anticheat/actions") @Title("Punish by the rules")
        @Describe("With 'Ban' on, a player reaching a check's ban level gets what the server's rule about "
                + "cheating (/rules) says instead of the fixed ban, counted like a moderator hands it out: 1st "
                + "offence, 2nd, … At most once every five minutes per player. Needs the rules and the moderation "
                + "module; without them, or when that rule has no punishments, the fixed ban is used.")
        @Key("punish-by-rules")
        boolean punishByRules,

        @In("anticheat/actions") @Title("Rule about cheating")
        @Describe("Which rule that is: its number in /rules, or 'auto' for the first rule whose title "
                + "mentions cheating or hacking.")
        @Key("cheating-rule")
        String cheatingRule,

        @In("anticheat/actions") @Title("Kick and ban levels, in percent") @Range(min = 10, max = 1000)
        @Describe("Scales every check's kick and ban level. 200 is twice as lenient, 50 twice as strict.")
        @Key("punish-scale")
        int punishScale,

        @In("anticheat/actions") @Title("Keep evidence")
        @Describe("Every failed check is written down with where it happened and the numbers behind it, "
                + "for /anticheat log.")
        @Key("evidence")
        boolean evidence,

        @In("anticheat/actions") @Title("Evidence kept per player") @Range(min = 10, max = 5000)
        @Describe("The newest this many failures per player are kept; older ones fall off.")
        @Key("evidence-per-player")
        int evidencePerPlayer,

        // ─────────────────────────────────────────────────────────── checks

        @In("anticheat/checks") @Title("Switched off")
        @Describe("Checks that do not run at all, by name: fly, speed, reach, autoclicker … "
                + "(/anticheat checks lists them).")
        @Key("disabled-checks")
        List<String> disabledChecks,

        @In("anticheat/checks") @Title("Watch only")
        @Describe("Checks that still alert and keep evidence but never set back, cancel, kick or ban — "
                + "for trying a check out on a new server.")
        @Key("silent-checks")
        List<String> silentChecks,

        @In("anticheat/checks") @Title("Experimental checks")
        @Describe("Statistical checks (aim, keep-sprint, post, autofish, chest stealer, auto totem). They "
                + "only ever alert, and only once their evidence piles up.")
        @Key("experimental-checks")
        boolean experimentalChecks,

        @In("anticheat/checks") @Title("Reach leniency")
        @Describe("Blocks of reach allowed on top of the game's own range, for latency. 0.3 catches 3.3+ "
                + "reach and leaves honest players alone.")
        @Key("reach-leniency")
        double reachLeniency,

        @In("anticheat/checks") @Title("Timer leniency") @Range(min = 50, max = 2000)
        @Describe("How many milliseconds a client's clock may run ahead before it counts as timer.")
        @Key("timer-leniency")
        int timerLeniency,

        @In("anticheat/checks") @Title("Clicks a second") @Range(min = 10, max = 100)
        @Describe("Clicks per second above this count as an autoclicker.")
        @Key("max-cps")
        int maxCps,

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

        @In("anticheat/actions") @Title("Dampen suspects' hits")
        @Describe("While somebody is clearly suspected in combat — a combat check at twice its alert level, or "
                + "Improbable — their melee hits do less damage, without telling them. A cheat stops winning "
                + "fights while a human looks into it.")
        @Key("dampen-suspects")
        boolean dampenSuspects,

        @In("anticheat/actions") @Title("Suspects' damage, in percent") @Range(min = 0, max = 100)
        @Describe("How much of their damage a dampened suspect still deals.")
        @Key("dampen-percent")
        int dampenPercent) {

    public static final AntiCheatSettings DEFAULTS = new AntiCheatSettings(
            true, true, 17.0, 600, true, false,
            true, true, true, true, false, "7d", true, "auto", 100, true, 200,
            List.of(), List.of(), true, 0.3, 150, 20,
            List.of("wurst", "meteor", "liquidbounce", "aristois", "rusherhack", "konas", "bleachhack",
                    "inertia", "novoline", "tenacity"),
            List.of("wurst", "meteor-client", "liquidbounce", "aristois", "bleachhack"),
            true, false, false, 64, false, 50);

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
