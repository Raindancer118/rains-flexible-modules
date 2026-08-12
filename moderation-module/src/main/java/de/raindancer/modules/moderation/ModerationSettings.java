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
import java.util.List;

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
        @Topic(path = "moderation/suspicious", title = "Suspicious commands", icon = Material.SPYGLASS),
        @Topic(path = "moderation/xray", title = "X-ray detection", icon = Material.DIAMOND_ORE),
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

        @In("moderation/punishments") @Title("Longest ban a mod may give")
        @Describe("Mods hold 'tempban' and admins hold 'ban'. This is the ceiling on the first: long "
                + "enough to stop a griefer at two in the morning, short enough that ending somebody's "
                + "time here for good stays an admin's decision. 'perm' would remove the distinction.")
        @Key("punishments.mod-tempban-max")
        String modTempBanMax,

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

        // ───────────────────────────────────────────────────────────── suspicious commands

        @In("moderation/suspicious") @Title("Watch for suspicious commands")
        @Describe("Typing one of the commands below files an automatic report on the player, the "
                + "same queue a moderator's own /report goes into. For a command that only makes "
                + "sense alongside a seed cracker or a similar outside tool — /seed is the obvious "
                + "one, since a seed is what a cracker needs to find every structure in the world.")
        @Key("suspicious.enabled")
        boolean suspiciousCommandsEnabled,

        @In("moderation/suspicious") @Title("Which commands")
        @Describe("Names, comma separated, without the slash and without arguments — 'seed' catches "
                + "'/seed' and '/seed confirm' alike. Matched against the first word only, so this "
                + "never catches a command that merely contains one of these as a substring.")
        @Key("suspicious.commands")
        List<String> suspiciousCommands,

        @In("moderation/suspicious") @Title("Wait before flagging the same player again")
        @Range(min = 0, max = 86400)
        @Describe("Seconds. Typing a watched command five times in a row should file one report, "
                + "not five — the queue exists to be read, and a moderator stops trusting it the "
                + "first time it is five identical lines about the same person. 0 files one every "
                + "time.")
        @Key("suspicious.cooldown-seconds")
        int suspiciousCooldownSeconds,

        // ───────────────────────────────────────────────────────────── x-ray detection

        @In("moderation/xray") @Title("Watch mining for x-ray")
        @Describe("The server cannot see a texture pack or a hacked client — what it can see is which "
                + "blocks somebody chooses to break. This watches the ratio of valuable ore to "
                + "everything else a player has mined recently, and files a report — the same queue "
                + "/report uses — when it looks like a pattern rather than luck.")
        @Key("xray.enabled")
        boolean xrayDetectionEnabled,

        @In("moderation/xray") @Title("Which blocks count as valuable")
        @Describe("Material names, comma separated, exactly as Bukkit spells them — "
                + "'DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE'. An unknown name is skipped rather than "
                + "refusing the whole list.")
        @Key("xray.ores")
        List<String> xrayOres,

        @In("moderation/xray") @Title("How many recent blocks to judge by") @Range(min = 20, max = 2000)
        @Describe("The ratio is taken over this many of the player's most recent mined blocks, not "
                + "their whole time on the server — a lucky vein an hour ago should not keep somebody "
                + "flagged for ever, and a window is what lets the number recover.")
        @Key("xray.window-blocks")
        int xrayWindowBlocks,

        @In("moderation/xray") @Title("Ore needed before the ratio means anything") @Range(min = 1, max = 50)
        @Describe("Below this many valuable blocks in the window, the ratio is not judged at all — "
                + "three diamonds in the first ten blocks of a fresh vein is a real ratio and not a "
                + "pattern.")
        @Key("xray.minimum-ore")
        int xrayMinimumOre,

        @In("moderation/xray") @Title("Ore share that counts as a pattern") @Range(min = 1, max = 100)
        @Describe("Percent of the window. Ordinary survival mining is nowhere near this even with a "
                + "good vein; x-ray digs almost nothing else. Set it too low and a lucky player is "
                + "reported; set it too high and nothing ever is — this is the one setting worth "
                + "watching the report queue for after changing.")
        @Key("xray.threshold-percent")
        int xrayThresholdPercent,

        @In("moderation/xray") @Title("Wait before flagging the same player again") @Range(min = 0, max = 86400)
        @Describe("Seconds. A player whose ratio stays high should file one report and then be left "
                + "to actually be looked at, not a fresh one on every ore block afterwards.")
        @Key("xray.cooldown-seconds")
        int xrayCooldownSeconds,

        @In("moderation/xray") @Title("Learn what is normal here, over time")
        @Describe("On, the threshold above can only be raised, never lowered, by what this server's "
                + "own players actually mine — a badlands or ancient-debris-rich seed has more "
                + "valuable ore per block of stone than an ordinary one, purely from terrain, and a "
                + "fixed percentage tuned for one is wrong for the other. Off uses the threshold "
                + "exactly as set.")
        @Key("xray.learn-from-server")
        boolean xrayLearningEnabled,

        @In("moderation/xray") @Title("How far above normal counts as suspicious") @Range(min = 2, max = 20)
        @Describe("A multiple of whatever this server's own players have actually been mining "
                + "lately. 5 means somebody has to be finding ore at five times the server's own "
                + "average rate before this counts towards flagging them. Only used when learning is "
                + "on, and only ever raises the threshold above — it can never excuse x-ray as normal "
                + "just because enough players are already doing it.")
        @Key("xray.learned-multiplier")
        int xrayLearnedMultiplier,

        @In("moderation/xray") @Title("VeinMiner installed")
        @Describe("On, only the first ore block of a chain a vein-mining plugin breaks in one go "
                + "counts towards the ratio or the review screen — the rest were never individually "
                + "found, they came along for free the moment the first one broke, and counting all "
                + "of them is how a lucky vein exposed in a lush cave or a ravine reads as a pattern "
                + "that was never there. Off treats every block VeinMiner breaks as its own find, "
                + "which is right for a server that does not run one at all.")
        @Key("xray.veinminer-mode")
        boolean xrayVeinminerModeEnabled,

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

        @In("moderation/staff") @Title("Staff may promote below themselves")
        @Describe("On lets a mod appoint a trial mod and an admin appoint a mod — each rank may hand "
                + "out the one below its own, and never its own or above. Off keeps every appointment "
                + "with the server owner.")
        @Key("staff.may-promote-below")
        boolean mayPromoteBelow,

        @In("moderation/staff") @Title("Staff may demote below themselves")
        @Describe("The same in reverse. Kept separate because the two are not the same trust: a server "
                + "may be happy for an admin to appoint mods while reserving the removing.")
        @Key("staff.may-demote-below")
        boolean mayDemoteBelow,

        @In("moderation/staff") @Title("Admins are operators")
        @Describe("Off, and deliberately. Op is not a permission — it is every permission of every "
                + "plugin on the server, plus /stop and /op itself, so an opped admin can promote "
                + "themselves and switch off whatever limits them. Turn this on only if your admins "
                + "are co-owners. Moderators are never opped, whatever this says.")
        @Key("staff.admins-are-op")
        boolean adminsAreOp,

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

    public ModerationSettings {
        suspiciousCommands = suspiciousCommands == null ? List.of() : List.copyOf(suspiciousCommands);
        xrayOres = xrayOres == null ? List.of() : List.copyOf(xrayOres);
    }

    /**
     * What a server gets before anybody changes anything.
     *
     * <p>Real Java rather than a list of untyped literals, so the compiler checks each one and a
     * renamed component is a build failure rather than a setting that silently reverts.
     */
    public static final ModerationSettings DEFAULTS = new ModerationSettings(
            true, true, false, false, false,
            "If you think this was a mistake, you can appeal on the website.",
            "perm", "1h", "15m", true, 3, 30, "perm", "1d", true,
            true, true,
            true, 120, 3, 8, true, true,
            true, List.of("seed", "seedcracker"), 600,
            true, List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS",
                    "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE"),
            200, 3, 8, 900, true, 5, false,
            true, true, "<dark_aqua>[Staff]</dark_aqua>", false, true, true, false, true,
            true, 0, 300, false);

    /** The report cooldown as the rule wants it. */
    public Duration reportCooldown() {
        return Duration.ofSeconds(Math.max(0, reportCooldownSeconds));
    }

    /** The wait before the same player can be auto-reported again, as the rule wants it. */
    public Duration suspiciousCooldown() {
        return Duration.ofSeconds(Math.max(0, suspiciousCooldownSeconds));
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
                useEscalation, warnsBeforeBan, warnWindowDays, warnBanLength, modTempBanMax,
                kickOnBan, mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, mayPromoteBelow,
                mayDemoteBelow, adminsAreOp, flightWhileVanished, auditEverything,
                keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAnnounceLifts(boolean announce) {
        return new ModerationSettings(announceToEveryone, announce, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAnnounceKicks(boolean announce) {
        return new ModerationSettings(announceToEveryone, announceLifts, announce, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAnnounceWarnings(boolean announce) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announce, showModeratorName,
                appealMessage, defaultBanLength, defaultMuteLength, defaultFreezeLength,
                useEscalation, warnsBeforeBan, warnWindowDays, warnBanLength, modTempBanMax,
                kickOnBan, mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, mayPromoteBelow,
                mayDemoteBelow, adminsAreOp, flightWhileVanished, auditEverything,
                keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withShowModeratorName(boolean named) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings, named,
                appealMessage, defaultBanLength, defaultMuteLength, defaultFreezeLength,
                useEscalation, warnsBeforeBan, warnWindowDays, warnBanLength, modTempBanMax,
                kickOnBan, mirrorToVanillaBanList, importVanillaBans, reportsEnabled,
                reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, mayPromoteBelow,
                mayDemoteBelow, adminsAreOp, flightWhileVanished, auditEverything,
                keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withWarnsBeforeBan(int howMany) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, howMany, warnWindowDays, warnBanLength,
                modTempBanMax, kickOnBan, mirrorToVanillaBanList, importVanillaBans,
                reportsEnabled, reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, mayPromoteBelow,
                mayDemoteBelow, adminsAreOp, flightWhileVanished, auditEverything,
                keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withAdminsAreOp(boolean opped) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, opped,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withModTempBanMax(String longest) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, longest, kickOnBan, mirrorToVanillaBanList, importVanillaBans,
                reportsEnabled, reportCooldownSeconds, mostOpenReportsPerPlayer, shortestReport,
                tellReporterWhenClosed, notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix, vanishOnJoinForStaff, mayPromoteBelow,
                mayDemoteBelow, adminsAreOp, flightWhileVanished, auditEverything,
                keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withMayPromoteBelow(boolean allowed) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, allowed, mayDemoteBelow, adminsAreOp, flightWhileVanished,
                auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withMayDemoteBelow(boolean allowed) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, allowed, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withSuspiciousCommandsEnabled(boolean enabled) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, enabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withSuspiciousCommands(List<String> commands) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, commands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withSuspiciousCooldownSeconds(int seconds) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                seconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayDetectionEnabled(boolean enabled) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                enabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayOres(List<String> ores) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, ores, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayWindowBlocks(int blocks) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, blocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayMinimumOre(int minimum) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, minimum, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayThresholdPercent(int percent) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, percent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayCooldownSeconds(int seconds) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                seconds, xrayLearningEnabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayLearningEnabled(boolean enabled) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, enabled, xrayLearnedMultiplier, xrayVeinminerModeEnabled, openReportsOnJoin,
                notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayLearnedMultiplier(int multiplier) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, multiplier, xrayVeinminerModeEnabled,
                openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    public ModerationSettings withXrayVeinminerModeEnabled(boolean enabled) {
        return new ModerationSettings(announceToEveryone, announceLifts, announceKicks, announceWarnings,
                showModeratorName, appealMessage, defaultBanLength, defaultMuteLength,
                defaultFreezeLength, useEscalation, warnsBeforeBan, warnWindowDays,
                warnBanLength, modTempBanMax, kickOnBan, mirrorToVanillaBanList,
                importVanillaBans, reportsEnabled, reportCooldownSeconds,
                mostOpenReportsPerPlayer, shortestReport, tellReporterWhenClosed,
                notifyStaffOnReport, suspiciousCommandsEnabled, suspiciousCommands,
                suspiciousCooldownSeconds,
                xrayDetectionEnabled, xrayOres, xrayWindowBlocks, xrayMinimumOre, xrayThresholdPercent,
                xrayCooldownSeconds, xrayLearningEnabled, xrayLearnedMultiplier, enabled,
                openReportsOnJoin, notesShownOnJoin, staffChatPrefix,
                vanishOnJoinForStaff, mayPromoteBelow, mayDemoteBelow, adminsAreOp,
                flightWhileVanished, auditEverything, keepRecordsDays, autoSaveSeconds, debug);
    }

    /** The wait before flagging the same player again, as the service wants it. */
    public java.time.Duration xrayCooldown() {
        return java.time.Duration.ofSeconds(Math.max(0, xrayCooldownSeconds));
    }
}
