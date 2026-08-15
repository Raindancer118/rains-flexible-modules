package de.raindancer.modules.essentials;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an owner can decide about the everyday stuff: spawn, AFK, and the words around joining,
 * leaving and being called something.
 */
@Settings(id = "essentials", topics = {
        @Topic(path = "essentials/spawn", title = "Spawn", icon = Material.RED_BED),
        @Topic(path = "essentials/afk", title = "AFK", icon = Material.CLOCK),
        @Topic(path = "essentials/social", title = "Messages & nicknames", icon = Material.WRITABLE_BOOK),
})
public record EssentialsSettings(

        @In("essentials/spawn") @Title("Stand still for") @Range(min = 0, max = 60)
        @Describe("Seconds /spawn makes somebody wait before sending them, so it is not a free way "
                + "out of a fight. Zero sends them at once.")
        int spawnWarmupSeconds,

        @In("essentials/afk") @Title("AFK detection")
        @Describe("Whether standing still, not talking and not typing a command marks somebody AFK "
                + "at all.")
        boolean afkEnabled,

        @In("essentials/afk") @Title("Away after") @Range(min = 15, max = 7200)
        @Describe("Seconds of no movement, chat or command before somebody is marked AFK.")
        int afkTimeoutSeconds,

        @In("essentials/afk") @Title("Announce it")
        @Describe("Whether going AFK and coming back are said to everybody, or kept to the "
                + "nametag and the player list alone.")
        boolean afkBroadcast,

        @In("essentials/social") @Title("Join and quit lines")
        @Describe("Whether this module says who joined and who left, in its own words below. Off "
                + "leaves the vanilla lines exactly as they are.")
        boolean joinQuitEnabled,

        @In("essentials/social") @Title("Welcome a new player")
        @Describe("Whether somebody's very first join gets its own broadcast, separate from the "
                + "ordinary join line.")
        boolean welcomeFirstJoin,

        @In("essentials/social") @Title("Nicknames")
        @Describe("Whether /nick exists at all. Off removes the command; a nickname already set "
                + "keeps working until it is switched back on.")
        boolean nicknamesEnabled,

        @In("essentials/social") @Title("Longest a nickname may be") @Range(min = 2, max = 32)
        @Describe("Characters, after colour and formatting are stripped away — what actually "
                + "appears in chat and the player list.")
        int nicknameMaxLength,

        @In("essentials/social") @Title("Blocked nicknames — reported")
        @Describe("Names nobody may wear as a nickname. Trying one is refused and reported to "
                + "staff automatically. Case-insensitive, colour and formatting ignored. Add "
                + "real-world names your server cares about here — the shipped list is a starting "
                + "point, not a complete one.")
        List<String> blockedNicknamesReported,

        @In("essentials/social") @Title("Blocked nicknames — reported and banned")
        @Describe("The same, and trying one also bans the player for a day, automatically — for "
                + "names severe enough that a report alone is not the right answer.")
        List<String> blockedNicknamesBanned

) {

    /**
     * A handful of common ways to spell the one name everybody agrees belongs on the severe list —
     * not exhaustive, and not the only name that could ever go here. A server adds its own through
     * this same setting; these ship only so the feature is not delivered empty.
     */
    private static final List<String> DEFAULT_BANNED = List.of(
            "hitler", "adolf hitler", "adolfhitler", "adolf_hitler", "a.hitler", "adolph hitler");

    /**
     * A couple of real people, named as the worked example for what this list is for — impersonating
     * a real, identifiable person. Deliberately not a long roster: a server adds whichever real names
     * it actually cares about through this same setting.
     */
    private static final List<String> DEFAULT_REPORTED = List.of("donald trump", "tom holland");

    public static final EssentialsSettings DEFAULTS =
            new EssentialsSettings(3, true, 300, true, true, true, true, 16, DEFAULT_REPORTED,
                    DEFAULT_BANNED);

    /** The reported-only blocklist, lower-cased once rather than on every {@code /nick}. */
    public Set<String> blockedReported() {
        return normalized(blockedNicknamesReported);
    }

    /** The report-and-ban blocklist, lower-cased once rather than on every {@code /nick}. */
    public Set<String> blockedBanned() {
        return normalized(blockedNicknamesBanned);
    }

    private static Set<String> normalized(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> lowered = new java.util.LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                lowered.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return lowered;
    }

    /** Clamped, so a hand-built settings record cannot make {@code /spawn} instant against its wish. */
    public int spawnWarmup() {
        return Math.max(0, Math.min(60, spawnWarmupSeconds));
    }

    /** Clamped, so a nought or negative timeout cannot mark somebody AFK the instant they join. */
    public int afkTimeout() {
        return Math.max(15, Math.min(7200, afkTimeoutSeconds));
    }

    /** Clamped, so a nickname cannot be asked to be longer than the setting that bounds it. */
    public int nicknameLimit() {
        return Math.max(2, Math.min(32, nicknameMaxLength));
    }
}
