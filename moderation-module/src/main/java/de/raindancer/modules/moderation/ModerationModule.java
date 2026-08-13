package de.raindancer.modules.moderation;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.moderation.listener.StaffChatListener;
import de.raindancer.modules.moderation.listener.StaffSessionListener;
import de.raindancer.modules.moderation.listener.SuspiciousCommandListener;
import de.raindancer.modules.moderation.listener.XrayWatchListener;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.rules.AnnouncementRule;
import de.raindancer.modules.moderation.rules.EscalationRule;
import de.raindancer.modules.moderation.rules.ReportRule;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.rules.SuspiciousCommandRule;
import de.raindancer.modules.moderation.rules.XrayRule;
import de.raindancer.modules.moderation.service.NoteService;
import de.raindancer.modules.moderation.service.PunishmentService;
import de.raindancer.modules.moderation.service.ReportService;
import de.raindancer.modules.moderation.service.StaffChatService;
import de.raindancer.modules.moderation.service.StaffService;
import de.raindancer.modules.moderation.service.SuspiciousCommandService;
import de.raindancer.modules.moderation.service.XrayDetectionService;
import de.raindancer.modules.moderation.service.WorldToolsService;
import de.raindancer.modules.moderation.store.NoteRegistry;
import de.raindancer.modules.moderation.store.NoteStorage;
import de.raindancer.modules.moderation.store.Reasons;
import de.raindancer.modules.moderation.store.ReportRegistry;
import de.raindancer.modules.moderation.store.ImmuneStaff;
import de.raindancer.modules.moderation.store.PendingNotices;
import de.raindancer.modules.moderation.store.PersistedFindings;
import de.raindancer.modules.moderation.store.PlayerMiningProfiles;
import de.raindancer.modules.moderation.store.ReportStorage;
import de.raindancer.modules.moderation.store.StaffRoster;
import de.raindancer.modules.moderation.util.PermissionNodes;
import de.raindancer.modules.moderation.util.Players;
import de.raindancer.modules.moderation.util.Words;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Moderation, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsModeration}, a plugin of its own. Hosted
 * inside {@code RainsSMPCore} it is one feature among several. The code below cannot tell which, and
 * that is the whole point of the arrangement.
 *
 * <h2>What enabling actually does — and what it deliberately does not</h2>
 * It reads the reports and the notes, wires up the commands and the screens, and registers two
 * listeners. It does <b>not</b> take ownership of who is banned. Punishments, the guard that turns them
 * away at the door, vanish, inventory viewing and the audit trail are all RainsCore's, and stay there.
 *
 * <p>That split is not tidiness. A server that removes this module keeps every ban it has already
 * handed out, keeps enforcing them, and keeps the record that answers the appeals — because none of
 * that ever lived here. The reverse arrangement, with the punishments in the module, is a plugin whose
 * removal quietly unbans everybody.
 *
 * <h2>What is left, then</h2>
 * The product: the commands somebody types, the screens they click, the reasons this server punishes
 * for and the ladder each one climbs, the report queue, the staff notes, and the decisions about who
 * hears what. That is a moderation <em>policy</em>, which is exactly the thing that should differ
 * between servers and should not be in a shared library.
 */
public final class ModerationModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("moderation", "Moderation", "2.16.1")
            .describedAs("Bans, mutes, reports, staff notes and the screens for them — over "
                    + "RainsCore's punishments, which stay whether or not this is installed")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<ModerationSettings> settings;

    private Reasons reasons;
    private ReportRegistry reports;
    private NoteRegistry notes;
    private ReportStorage reportStorage;
    private NoteStorage noteStorage;
    private ImmuneStaff immune;
    private PendingNotices pending;
    private PlayerMiningProfiles miningProfiles;
    private PersistedFindings miningFindings;

    private StaffRule staffRule;
    private EscalationRule escalation;
    private AnnouncementRule announcements;

    private PunishmentService punishmentService;
    private ReportService reportService;
    private SuspiciousCommandService suspiciousCommands;
    private XrayDetectionService xrayDetection;
    private NoteService noteService;
    private StaffChatService staffChat;
    private StaffRoster roster;
    private StaffService staffService;
    private WorldToolsService worldTools;

    private StaffChatListener staffChatListener;
    private ModerationServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(ModerationSettings.class, ModerationSettings.DEFAULTS);

        // The module's own wording, offered as a default below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw
        // away Core's own lines and every other module's with them.
        // Core's own loader, and signed with this module's brand so its lines say Moderation and
        // nobody else's say it. See Messages#prefixFor.
        // Beside this class rather than at the jar root. RainsCore ships a messages.yml at its own
        // root and join-classpath puts it on this module's classpath, so a root lookup is a race
        // between two files with the same name — and in a jar carrying several modules it is a race
        // with whatever else lands there too. The loser speaks in keys.
        context.core().messages().defineFrom(
                getClass().getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks about a permission. Staff are not operators, so every node has to be
        // registered with a default or hasPermission answers false for everybody who is not op — which
        // is right for the staff nodes and would make /report refuse every player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        reasons = Reasons.builtIn();
        reports = new ReportRegistry();
        notes = new NoteRegistry();
        reportStorage = new ReportStorage(context.dataFolder());
        noteStorage = new NoteStorage(context.dataFolder());
        immune = new ImmuneStaff(context.dataFolder());
        immune.load();
        pending = new PendingNotices(context.dataFolder());
        pending.load();
        miningProfiles = new PlayerMiningProfiles(context.dataFolder());
        miningFindings = new PersistedFindings(context.dataFolder());

        // ── the rules ─────────────────────────────────────────────────────────────────────────
        // The permission lookup goes through a Player rather than an OfflinePlayer, and that is
        // right for what a moderator may *do*: an offline account is not running commands, so a
        // permission plugin having no answer for it costs nothing.
        //
        // What may be done *to* them is a different question and no longer asks this at all — see
        // the protection seam below.
        staffRule = new StaffRule(
                (who, node) -> {
                    if (who == null) {
                        return true;    // the console
                    }
                    Player here = server.getPlayer(who);
                    return here != null && here.hasPermission(node);
                },
                // Protection, which is deliberately not a permission. A permission plugin can only
                // answer for somebody who is online, and the subject of a ban usually is not — so it
                // is the console-written list, plus operators.
                //
                // Operators are covered without being on the list so that a fresh server is never in
                // the window where an admin can ban the owner before anybody has typed /protect.
                // OfflinePlayer#isOp reads ops.json rather than asking a permission plugin, so it is
                // true of somebody who has been offline for a month.
                subject -> subject != null
                        && (immune.isImmune(subject) || server.getOfflinePlayer(subject).isOp()));
        escalation = new EscalationRule();
        announcements = new AnnouncementRule();

        // ── the services ──────────────────────────────────────────────────────────────────────
        punishmentService = new PunishmentService(context.plugin(), server,
                context.core().punishments(), context.core().punishmentGuard(),
                context.core().banBridge(), context.core().audit(), context.core().messages(),
                context.chat(), pending, announcements, escalation, settings.current());
        reportService = new ReportService(context.plugin(), server, reports, reportStorage,
                context.core().audit(), context.core().messages(), context.chat(), pending,
                this::filingRule, settings.current());
        suspiciousCommands = new SuspiciousCommandService(reportService, new SuspiciousCommandRule(),
                settings.current());
        xrayDetection = new XrayDetectionService(reportService, new XrayRule(), miningProfiles,
                miningFindings, settings.current());
        xrayDetection.load();
        noteService = new NoteService(context.plugin(), notes, noteStorage, context.core().audit(),
                settings.current());
        staffChat = new StaffChatService(settings.current());
        worldTools = new WorldToolsService(context.plugin(), server, context.core().audit(),
                log, settings.current());

        // Who is staff, at what rank. The nodes themselves are Core's Grants — see StaffRoster for why
        // the label and the power are kept apart.
        roster = new StaffRoster(context.dataFolder(), context.core().grants());
        roster.load();
        staffService = new StaffService(context.plugin(), server, roster, context.core().grants(),
                context.core().audit(), context.core().messages(), context.chat(),
                context.core()::reapplyGrants, settings.current());

        reportService.load();
        noteService.load();
        log.info("{} report(s) and {} staff note(s) loaded.", reports.size(), notes.size());

        // A ban typed into the console before this was installed is brought into the record rather
        // than silently ignored — otherwise /history says a banned player has a clean sheet.
        if (settings.current().importVanillaBans()) {
            int imported = context.core().banBridge().importAll();
            if (imported > 0) {
                log.info("{} ban(s) from the server's own list were brought into the record.",
                        imported);
            }
        }
        context.core().punishmentGuard().appealMessage(settings.current().appealMessage());

        services = new ModerationServices(context.plugin(), server, log, context.core().messages(),
                context.chat(), context.chat().brand(), context.core().prompts(),
                context.core().settingsNavigation(),
                context.core().punishments(), context.core().punishmentGuard(),
                context.core().vanish(), context.core().players(), context.core().powers(),
                context.core().inventories(),
                context.core().audit(), context.core().grants(), () -> directoryOf(server),
                reasons, reports, notes, staffRule, escalation, announcements, this::standingRule,
                this::banLimitRule, this::promotionRule, this::filingRule,
                punishmentService, reportService, suspiciousCommands, xrayDetection, noteService, staffChat, roster, immune,
                staffService, worldTools,
                () -> staffChatListener,
                settings::current, new LiveScreens());

        staffChatListener = new StaffChatListener(services);
        StaffSessionListener session = new StaffSessionListener(services, pending)
                .alsoTelling(staffChatListener);

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of
        // these is a subsystem that keeps yesterday's numbers until the next restart, which is the
        // sort of defect that gets reported as "the config does not work".
        settings.onChange(fresh -> {
            punishmentService.settings(fresh);
            reportService.settings(fresh);
            suspiciousCommands.settings(fresh);
            xrayDetection.settings(fresh);
            noteService.settings(fresh);
            staffChat.settings(fresh);
            staffService.settings(fresh);
            worldTools.settings(fresh);
            context.core().punishmentGuard().appealMessage(fresh.appealMessage());
        });

        context.listener(session);
        context.listener(staffChatListener);
        context.listener(new SuspiciousCommandListener(services));
        context.listener(new XrayWatchListener(services));

        // Reports and notes reach the disk on a timer as well as on every change: the per-change save
        // is asynchronous and can fail, and a queue that only reaches disk on shutdown is one crash
        // away from a player asking what happened to the report they filed an hour ago.
        int every = settings.current().autoSaveSeconds();
        if (every > 0) {
            var writing = Scheduling.asyncTimer(context.plugin(), every, every, task -> {
                reportService.flush();
                noteService.flush();
                xrayDetection.flush();
            });
            context.closeWith(writing::cancel);
        }

        // The commands were registered during bootstrap, long before any of this existed, and have
        // been answering "not started yet" until now. See ModerationCommands.
        ModerationCommands.ready(services);

        log.info("Moderation is up: {} reason(s), {} report(s) waiting, {} staff.", reasons.size(),
                reports.waitingCount(), roster.size());
    }

    /**
     * Rebuilt per ask, because its window is a setting.
     *
     * <p>Same reason as {@link #filingRule()}: one built at startup keeps yesterday's window until
     * the next restart, and the owner who widened it sees nothing change.
     */
    private de.raindancer.modules.moderation.rules.StandingRule standingRule() {
        return new de.raindancer.modules.moderation.rules.StandingRule(settings.current().warnWindow());
    }

    /**
     * Who may hand out which rank, with the settings as they are now.
     *
     * <p>The owner is whoever holds the promote node — which is op by default and is deliberately in no
     * preset, so nothing this module grants can ever confer it.
     */
    private de.raindancer.modules.moderation.rules.PromotionRule promotionRule() {
        return new de.raindancer.modules.moderation.rules.PromotionRule(
                who -> {
                    if (who == null) {
                        return true;    // the console
                    }
                    org.bukkit.entity.Player here = services.server().getPlayer(who);
                    return here != null && (here.isOp() || here.hasPermission(
                            de.raindancer.modules.moderation.command.PromoteCommand.USE));
                },
                roster::rankOf,
                settings.current().mayPromoteBelow(),
                settings.current().mayDemoteBelow());
    }

    /** Rebuilt per ask, because the cap is a setting. See {@code BanLimitRule}. */
    private de.raindancer.modules.moderation.rules.BanLimitRule banLimitRule() {
        java.time.Duration cap = de.raindancer.modules.moderation.model.Sentence
                .parse(settings.current().modTempBanMax())
                .flatMap(de.raindancer.modules.moderation.model.Sentence::length)
                // Unreadable, or "perm": both mean the setting says no ceiling, and a ceiling nobody
                // can read must not silently become "no bans at all".
                .orElse(java.time.Duration.ofDays(365));
        return new de.raindancer.modules.moderation.rules.BanLimitRule(staffRule, cap);
    }

    /** Rebuilt per ask, because the limits are settings and a reload has to change what happens next. */
    private ReportRule filingRule() {
        ModerationSettings now = settings.current();
        return new ReportRule(now.reportCooldown(), now.mostOpenReportsPerPlayer(),
                now.shortestReport());
    }

    /**
     * Everybody the server has seen, as Core's directory.
     *
     * <p>Built when a chooser opens rather than held: it reads the player data directory, and one
     * captured at startup does not contain the player who joined this evening.
     */
    private PlayerDirectory directoryOf(Server server) {
        // Vanished players are fed in as offline. Hiding an entity does not hide somebody from a list a
        // plugin builds itself, and a chooser showing a vanished moderator as online is the one place
        // anybody would look to check whether they are about.
        return new PlayerDirectory(
                () -> Players.everybody(server, services.vanish().everybodyVanished()),
                System::currentTimeMillis);
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>An inner class rather than ten lambdas at the construction site: it reads as a list of the
     * screens this module has, and a new screen is one method rather than one more argument.
     */
    private final class LiveScreens implements ModerationScreensOpener {

        @Override
        public void player(Player viewer, UUID subject, String subjectName) {
            new de.raindancer.modules.moderation.screen.PlayerMenu(services, viewer, null, subject,
                    subjectName).open();
        }

        @Override
        public void player(Player viewer, OfflinePlayer subject) {
            player(viewer, subject.getUniqueId(), Players.nameOf(subject));
        }

        @Override
        public void pickPlayer(Player viewer) {
            new de.raindancer.modules.moderation.screen.PlayerPickerMenu(services, viewer, null)
                    .open();
        }

        @Override
        public void notes(Player viewer, UUID subject, String subjectName) {
            new de.raindancer.modules.moderation.screen.NotesMenu(services, viewer, null, subject,
                    subjectName).open();
        }

        @Override
        public void reports(Player viewer) {
            new de.raindancer.modules.moderation.screen.ReportsMenu(services, viewer, null).open();
        }

        @Override
        public void reportCategories(Player viewer, UUID subject, String subjectName) {
            new de.raindancer.modules.moderation.screen.ReportCategoryMenu(services, viewer, null,
                    subject, subjectName).open();
        }

        @Override
        public void pickSomebodyToReport(Player viewer) {
            new de.raindancer.modules.moderation.screen.WhoToReportMenu(services, viewer, null).open();
        }

        @Override
        public void worldTools(Player viewer) {
            new de.raindancer.modules.moderation.screen.WorldToolsMenu(services, viewer, null).open();
        }

        @Override
        public void staff(Player viewer) {
            new de.raindancer.modules.moderation.screen.StaffMenu(services, viewer, null).open();
        }

        @Override
        public void xraySuspicion(Player viewer) {
            new de.raindancer.modules.moderation.screen.XraySuspicionMenu(services, viewer, null)
                    .open();
        }
    }

    /**
     * The commands, declared at bootstrap.
     *
     * <p>Paper wants them before anything is enabled, so they are built pointing at a supplier that is
     * filled in when this module starts. Until then the host's guard answers with one line saying so.
     */
    @Override
    public List<ModuleCommand> commands() {
        return ModerationCommands.declared();
    }

    @Override
    public void disable() {
        ModerationCommands.stopped();
        // Before anything else. A wave outliving its module is a wave nothing can stop: its tasks
        // would keep firing against services that have been stood down, and the only way out would be
        // a restart.
        if (worldTools != null) {
            int stopped = worldTools.stopEverything();
            if (stopped > 0) {
                log.info("{} wave(s) still running were stopped.", stopped);
            }
        }
        // Written whether or not anything is marked dirty: a shutdown has no next pass, and the cost
        // of one unnecessary write is nothing against the cost of losing the queue.
        if (reportService != null && !reportService.flushNow()) {
            log.error("The report queue could not be written on shutdown.");
        }
        if (noteService != null && !noteService.flushNow()) {
            log.error("The staff notes could not be written on shutdown.");
        }
        if (immune != null && !immune.flush()) {
            log.error("The list of protected accounts could not be written on shutdown.");
        }
        if (xrayDetection != null && !xrayDetection.flush()) {
            log.error("The x-ray suspicion profiles could not be written on shutdown.");
        }
        // What nobody has been told yet. Losing these on a restart is the whole thing PendingNotices
        // exists to stop, and a shutdown is when it would happen.
        if (pending != null && !pending.flush()) {
            log.error("Undelivered notices could not be written on shutdown.");
        }
        if (roster != null && !roster.flush()) {
            log.error("The staff roster could not be written on shutdown.");
        }
        // The listeners are unregistered and the save timer cancelled by the context, in the reverse
        // order everything was registered — see ModuleContext.closeWith.
    }

    /** The reports, for a host that wants to look at them. */
    public ReportRegistry reports() {
        return reports;
    }

    /** The staff notes. */
    public NoteRegistry notes() {
        return notes;
    }

    /** What this server punishes for. */
    public Reasons reasons() {
        return reasons;
    }
}
