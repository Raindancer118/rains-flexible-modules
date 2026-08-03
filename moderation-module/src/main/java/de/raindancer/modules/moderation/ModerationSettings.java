package de.raindancer.modules.moderation;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.time.Duration;

/**
 * Everything a server owner can decide about moderation, as one record.
 *
 * <h2>Why a record</h2>
 * The record <em>is</em> the schema: {@code config.yml}, its comments, validation, tab completion and
 * the settings GUI are all derived from it, so there is nothing to keep in step and no second copy to
 * fail a test over. The defaults are real Java in {@link #DEFAULTS}, checked by the compiler rather
 * than being untyped literals in a list.
 *
 * <h2>What is deliberately not here</h2>
 * The reasons and their ladders. Those are {@code store.Reasons}, in code, because a mis-typed ladder
 * is a permanent ban and the compiler and {@code ReasonsTest} between them will not let one through. A
 * server that wants its own set hands the module a different {@code Reasons}.
 *
 * <p>Also not here: anything about what a punishment <em>is</em>. Bans, mutes and freezes belong to
 * RainsCore, so their storage, their expiry and their enforcement are configured there — a server that
 * removes this module keeps every ban it has already handed out.
 */
@Settings(id = "moderation", topics = {
        @Topic(path = "moderation", title = "Moderation", icon = Material.IRON_BARS),
        @Topic(path = "moderation/punishments", title = "Punishments", icon = Material.BARRIER),
        @Topic(path = "moderation/announcing", title = "Who hears about it", icon = Material.BELL),
        @Topic(path = "moderation/vanilla", title = "The server's own ban list",
                icon = Material.COMMAND_BLOCK),
        @Topic(path = "moderation/reports", title = "Reports", icon = Material.PAPER),
        @Topic(path = "moderation/staff", title = "Staff", icon = Material.PLAYER_HEAD),
        @Topic(path = "moderation/records", title = "Records", icon = Material.BOOKSHELF),
})
public record ModerationSettings(

        // ───────────────────────────────────────────────────────────── who hears about it

        @In("moderation/announcing") @Title("Announce to everybody")
        @Describe("Off keeps every punishment to the staff. A ban is a fact about the server that "
                + "everybody notices anyway; some servers would still rather say nothing.")
        @Key("announce.everyone")
        boolean announceToEveryone,

        @In("moderation/announcing") @Title("Announce lifts too")
        @Describe("A ban announced to the server and lifted in silence is how a rumour outlives the "
                + "ban by a year.")
        @Key("announce.lifts")
        boolean announceLifts,

        @In("moderation/announcing") @Title("Announce kicks")
        @Describe("Off by default: most kicks look like a connection problem to everybody watching.")
        @Key("announce.kicks")
        boolean announceKicks,

        @In("moderation/announcing") @Title("Announce warnings")
        @Describe("Off by default: a warning announced to the room is a punishment on top of the "
                + "warning.")
        @Key("announce.warnings")
        boolean announceWarnings,

        @In("moderation/announcing") @Title("Name the moderator publicly")
        @Describe("Staff always see who did it. On puts the name in the public line as well, which is "
                + "how a moderator gets followed around by the friends of whoever they banned.")
        @Key("announce.show-moderator")
        boolean showModeratorName,

        // ───────────────────────────────────────────────────────────── punishments

        @In("moderation/punishments") @Title("What a banned player is told")
        @Describe("Shown under the reason when somebody is turned away at the door. Put your appeals "
                + "link here.")
        @Key("punishments.appeal-message")
        String appealMessage,

        @In("moderation/punishments") @Title("Default ban length")
        @Describe("Used when a moderator types no length at all. 'perm' for until somebody lifts it.")
        @Key("punishments.default-ban-length")
        String defaultBanLength,

        @In("moderation/punishments") @Title("Default mute length")
        @Key("punishments.default-mute-length")
        String defaultMuteLength,

        @In("moderation/punishments") @Title("Default freeze length")
        @Describe("Short on purpose: a freeze is what a moderator does while walking over to talk to "
                + "somebody, not a punishment of its own.")
        @Key("punishments.default-freeze-length")
        String defaultFreezeLength,

        @In("moderation/punishments") @Title("Suggest a length from the record")
        @Describe("On makes a reason's ladder decide: the second offence is longer than the first. Off "
                + "makes every preset use its first rung, and the presets become names only.")
        @Key("punishments.escalate")
        boolean useEscalation,

        @In("moderation/punishments") @Title("Warnings before a ban") @Range(min = 0, max = 50)
        @Describe("When somebody collects this many warnings inside the window below, they are banned "
                + "automatically. 0 switches it off, and warnings then only ever sit on the record.")
        @Key("punishments.warns-before-ban")
        int warnsBeforeBan,

        @In("moderation/punishments") @Title("Warnings count for") @Range(min = 1, max = 3650)
        @Describe("Days. A warning older than this no longer counts towards the threshold — without a "
                + "window, a bad week two summers ago would still be banning people today.")
        @Key("punishments.warn-window-days")
        int warnWindowDays,

        @In("moderation/punishments") @Title("How long that ban is for")
        @Describe("The length of the automatic ban. 'perm' for until somebody lifts it.")
        @Key("punishments.warn-ban-length")
        String warnBanLength,

        @In("moderation/punishments") @Title("Kick when banning")
        @Describe("Off means a ban applies at the next login, which is how somebody stays on the "
                + "server for another hour after being banned.")
        @Key("punishments.kick-on-ban")
        boolean kickOnBan,

        // ───────────────────────────────────────────────────────────── the server's own ban list

        @In("moderation/vanilla") @Title("Write bans to the server's own list")
        @Describe("On keeps vanilla /banlist in step, and means a ban survives this plugin being "
                + "removed.")
        @Key("punishments.mirror-vanilla-ban-list")
        boolean mirrorToVanillaBanList,

        @In("moderation/vanilla") @Title("Read bans from the server's own list")
        @Describe("On brings a ban typed into the console before this was installed into the record, "
                + "rather than silently ignoring it.")
        @Key("punishments.import-vanilla-bans")
        boolean importVanillaBans,

        // ───────────────────────────────────────────────────────────── reports

        @In("moderation/reports") @Title("Players may report each other")
        @Key("reports.enabled")
        boolean reportsEnabled,

        @In("moderation/reports") @Title("Wait between reports") @Range(min = 0, max = 3600)
        @Describe("Seconds. 0 for no wait at all — a small server where everybody knows everybody "
                + "wants none of these limits.")
        @Key("reports.cooldown-seconds")
        int reportCooldownSeconds,

        @In("moderation/reports") @Title("Most open at once, per player") @Range(min = 0, max = 100)
        @Describe("0 for no limit. Their closed reports do not count.")
        @Key("reports.most-open-per-player")
        int mostOpenReportsPerPlayer,

        @In("moderation/reports") @Title("Shortest report") @Range(min = 0, max = 200)
        @Describe("Characters. Enough to exclude 'hes bad' without turning a report into an essay.")
        @Key("reports.shortest-report")
        int shortestReport,

        @In("moderation/reports") @Title("Tell the reporter when it is dealt with")
        @Describe("'Nobody looked at it' and 'somebody looked and there was nothing in it' are "
                + "different answers, and the player who filed it deserves the second one.")
        @Key("reports.tell-reporter")
        boolean tellReporterWhenClosed,

        @In("moderation/reports") @Title("Tell the staff when one arrives")
        @Key("reports.notify-staff")
        boolean notifyStaffOnReport,

        // ───────────────────────────────────────────────────────────── staff

        @In("moderation/staff") @Title("Show waiting reports on joining")
        @Describe("What somebody coming on shift needs to know before anything else.")
        @Key("staff.open-reports-on-join")
        boolean openReportsOnJoin,

        @In("moderation/staff") @Title("Show staff notes when somebody joins")
        @Describe("A quiet line to the staff when a player with notes on them comes on.")
        @Key("staff.notes-on-join")
        boolean notesShownOnJoin,

        @In("moderation/staff") @Title("Staff chat prefix")
        @Describe("MiniMessage. Marks the channel, or it is indistinguishable from ordinary chat.")
        @Key("staff.chat-prefix")
        String staffChatPrefix,

        @In("moderation/staff") @Title("Vanish staff on joining")
        @Describe("Off by default: vanishing somebody without asking is how a moderator spends an "
                + "evening wondering why nobody answers them.")
        @Key("staff.vanish-on-join")
        boolean vanishOnJoinForStaff,

        @In("moderation/staff") @Title("Let a vanished moderator fly")
        @Key("staff.flight-while-vanished")
        boolean flightWhileVanished,

        // ───────────────────────────────────────────────────────────── records

        @In("moderation/records") @Title("Write everything to the audit trail")
        @Describe("Every punishment, lift, note and report. This is what an appeal is answered from.")
        @Key("records.audit")
        boolean auditEverything,

        @In("moderation/records") @Title("Keep records for") @Range(min = 0, max = 3650)
        @Describe("Days. 0 keeps them for ever — a moderation record that expires is one an appeal "
                + "cannot be answered from.")
        @Key("records.keep-days")
        int keepRecordsDays,

        @In("moderation/records") @Title("Save reports and notes every") @Range(min = 0, max = 3600)
        @Describe("Seconds. 0 writes only on shutdown, which is one crash away from losing the queue.")
        @Key("records.auto-save-seconds")
        int autoSaveSeconds,

        @In("moderation/records") @Title("Debug")
        @Describe("Says in the console what every rule decided and why.")
        // Under a section rather than bare. Core keeps one settings registry for the whole server, so
        // a plain `debug` is a word another plugin may already own — and on the test server it was:
        // claims declares one too, and Core warned that `/settings debug` reaches whichever plugin
        // registered first.
        @Key("records.debug")
        boolean debug) {

    /**
     * What a server gets before anybody changes anything.
     *
     * <p>Real Java rather than a list of untyped literals, so the compiler checks each one and a
     * renamed component is a build failure rather than a setting that silently reverts.
     */
    public static final ModerationSettings DEFAULTS = new ModerationSettings(
            true, true, false, false, false,
            "If you think this was a mistake, you can appeal on the website.",
            "perm", "1h", "15m", true, 3, 30, "perm", true,
            true, true,
            true, 120, 3, 8, true, true,
            true, true, "<dark_aqua>[Staff]</dark_aqua>", false, true,
            true, 0, 300, false);

    /** The report cooldown as the rule wants it. */
    public Duration reportCooldown() {
        return Duration.ofSeconds(Math.max(0, reportCooldownSeconds));
    }

    /** How long records are kept, or empty for for ever. */
    public java.util.Optional<Duration> recordsKeptFor() {
        return keepRecordsDays <= 0 ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.ofDays(keepRecordsDays));
    }

    // ────────────────────────────────────────────────────────────────────────────────────────
    //  The five values a caller genuinely varies on its own.
    //
    //  Written out rather than generated: a record has one positional constructor, and anything
    //  that spells all twenty-eight components out is a mis-ordering waiting to happen. Each of
    //  these changes exactly one word from the line above it, which is a diff a reviewer can check.
    // ────────────────────────────────────────────────────────────────────────────────────────

    /** How far back a warning still counts towards {@link #warnsBeforeBan()}. */
    public Duration warnWindow() {
        return Duration.ofDays(Math.max(1, warnWindowDays));
    }

    /** Whether warnings add up to a ban at all. */
    public boolean warningsEscalateToABan() {
        return warnsBeforeBan > 0;
    }

    // ────────────────────────────────────────────────────────────────────────────────────────
    //  The values a caller varies on its own.
    //
    //  Generated rather than hand-written: a record has one positional constructor, and anything
    //  that spells all thirty-one components out by hand is a mis-ordering waiting to happen —
    //  two swapped booleans compile perfectly. Each of these changes exactly one name.
    // ────────────────────────────────────────────────────────────────────────────────────────

    public ModerationSettings withAnnounceToEveryone(boolean announce) {
        return new ModerationSettings(announce, announceLifts, announceKicks, announceWarnings, showModeratorName,
                appealMessage, defaultBanLength, defaultMuteLength, defaultFreezeLength,
                useEscalation, warnsBeforeBan, warnWindowDays, warnBanLength, kickOnBan,
                mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAnnounceLifts(boolean announce) {
        return new ModerationSettings(announceToEveryone, announce, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, kickOnBan, mirrorToVanillaBanList, importVanillaBans,
                reportsEnabled, reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAnnounceKicks(boolean announce) {
        return new ModerationSettings(announceToEveryone, announceLifts, announce, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, kickOnBan, mirrorToVanillaBanList, importVanillaBans,
                reportsEnabled, reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAnnounceWarnings(boolean announce) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announce, showModeratorName,
                appealMessage, defaultBanLength, defaultMuteLength, defaultFreezeLength,
                useEscalation, warnsBeforeBan, warnWindowDays, warnBanLength, kickOnBan,
                mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withShowModeratorName(boolean named) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings, named,
                appealMessage, defaultBanLength, defaultMuteLength, defaultFreezeLength,
                useEscalation, warnsBeforeBan, warnWindowDays, warnBanLength, kickOnBan,
                mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withWarnsBeforeBan(int howMany) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, howMany, warnWindowDays, warnBanLength,
                kickOnBan, mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }
}
