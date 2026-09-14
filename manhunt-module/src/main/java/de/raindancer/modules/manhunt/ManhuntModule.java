package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.screen.ManhuntAchievementsMenu;
import de.raindancer.modules.manhunt.screen.ManhuntChaosMenu;
import de.raindancer.modules.manhunt.screen.ManhuntLobbyMenu;
import de.raindancer.modules.manhunt.screen.ManhuntOptionsMenu;
import de.raindancer.modules.manhunt.screen.ManhuntFieldMenu;
import de.raindancer.modules.manhunt.screen.ManhuntTrackerMenu;
import de.raindancer.modules.manhunt.service.ChaosService;
import de.raindancer.modules.manhunt.service.HuntHistory;
import de.raindancer.modules.manhunt.service.ManhuntAchievements;
import de.raindancer.modules.manhunt.service.ManhuntLobbyBox;
import de.raindancer.modules.manhunt.service.ManhuntLobbyListener;
import de.raindancer.modules.manhunt.service.ManhuntService;
import de.raindancer.modules.manhunt.service.ManhuntDeathListener;
import de.raindancer.modules.manhunt.service.ManhuntEndOfRun;
import de.raindancer.modules.manhunt.service.ManhuntNarrationListener;
import de.raindancer.modules.manhunt.service.ManhuntNarrator;
import de.raindancer.modules.manhunt.service.ManhuntChatListener;
import de.raindancer.modules.manhunt.service.ManhuntRules;
import de.raindancer.modules.manhunt.service.ManhuntSpectators;
import de.raindancer.modules.manhunt.service.SideChat;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.service.PortalMemory;
import de.raindancer.modules.manhunt.service.TrackerCompass;
import de.raindancer.modules.manhunt.service.TrackerCompassService;
import de.raindancer.modules.manhunt.service.TrackerListener;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import de.raindancer.modules.speedrun.SpeedrunCompanions;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "RainsManhunt", as a module.
 *
 * <h2>What is deliberately not here</h2>
 * The speedrun timer, the pause-while-everybody-is-offline behaviour, and throwing a world away and
 * making it again are all {@code speedrun-module}'s — see {@code ChainedModule}'s own class javadoc
 * for why that engine lives there rather than in RainsCore. This module is the two sides of the hunt,
 * a win condition each of them can pick independently, the head start, the real server whitelist a
 * Runner can open and close, and a handful of live chaos actions.
 */
public final class ManhuntModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("manhunt", "Manhunt", "0.6.3")
            .describedAs("Runners against Hunters on top of speedrun-module's engine — a win "
                    + "condition per side, a tracking compass that follows a Runner through the "
                    + "portal they took, a real server whitelist a Runner can open and close, "
                    + "live chaos actions a host can throw at a running match, and a remembered "
                    + "history of every hunt that has ever finished.")
            .by("Raindancer118");

    private ManhuntService manhunt;
    private ManhuntServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        LogChannel log = context.log();
        Server server = context.plugin().getServer();

        // The module's own wording, offered as a floor below anything the owner has written — see
        // ChainedModule's own note on why this is defineFrom rather than Messages.load.
        context.core().messages().defineFrom(
                ManhuntModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        SettingsStore<ManhuntSettings> settings = context.settings(ManhuntSettings.class,
                ManhuntSettings.DEFAULTS);

        ManhuntTeams teams = new ManhuntTeams(() -> manhunt != null && manhunt.isRunning());
        ManhuntService liveManhunt = new ManhuntService(context.plugin(), teams,
                context.core().bossBars(), context.core().messages(), settings.current());
        this.manhunt = liveManhunt;
        settings.onChange(liveManhunt::settings);

        ChaosService chaos = new ChaosService(context.plugin(), liveManhunt);
        ManhuntWhitelistService whitelist = new ManhuntWhitelistService(server);

        // The waiting lobby: continuous from plugin startup, since a player may join a side at any
        // time — unlike HunterHoldListener/SpeedrunOccupancyListener, which are only ever registered
        // per-run inside ManhuntService itself, this one is registered once, here.
        ManhuntLobbyBox lobbyBox = new ManhuntLobbyBox(settings.current());
        settings.onChange(lobbyBox::settings);
        ManhuntLobbyListener lobbyListener = new ManhuntLobbyListener(lobbyBox, context.core().messages());
        server.getPluginManager().registerEvents(lobbyListener, context.plugin());

        // The tracking compass. Registered once, like the waiting lobby and for the same reason: each
        // of its handlers already asks whether a hunt is running, and one pair of moments where a
        // crash could leave a listener behind is one too many.
        PortalMemory portals = new PortalMemory();
        TrackerCompass trackerCompass = new TrackerCompass(settings.current(), portals);
        settings.onChange(trackerCompass::settings);
        TrackerCompassService tracker = new TrackerCompassService(context.plugin(), liveManhunt,
                trackerCompass, portals, context.core().messages(), settings.current());
        settings.onChange(tracker::settings);
        server.getPluginManager().registerEvents(
                new TrackerListener(liveManhunt, tracker, portals), context.plugin());

        // What a death costs, and what happens once it is all over. Both registered once, like the
        // waiting lobby: each asks whether a hunt is running before it does anything.
        ManhuntDeathListener deaths = new ManhuntDeathListener(context.plugin(), liveManhunt,
                liveManhunt.lives(), context.core().messages(), settings.current());
        settings.onChange(deaths::settings);
        server.getPluginManager().registerEvents(deaths, context.plugin());

        ManhuntEndOfRun endOfRun = new ManhuntEndOfRun(context.plugin(), teams, lobbyListener,
                settings.current());
        settings.onChange(endOfRun::settings);

        // What the hunt says out loud. Its own one-second timer, armed with the hunt — see the
        // narrator's own note on why that beats a third hook on ManhuntService.
        ManhuntNarrator narrator = new ManhuntNarrator(context.plugin(), liveManhunt,
                context.core().messages(), settings.current());
        settings.onChange(narrator::settings);
        server.getPluginManager().registerEvents(
                new ManhuntNarrationListener(liveManhunt, liveManhunt.lives(), narrator), context.plugin());

        // Talking to your own side, the rules a hunt borrows, and watching from outside it.
        SideChat sideChat = new SideChat(settings.current());
        settings.onChange(sideChat::settings);
        server.getPluginManager().registerEvents(
                new ManhuntChatListener(liveManhunt, sideChat, context.core().messages()), context.plugin());

        ManhuntRules rules = new ManhuntRules(context.plugin(), liveManhunt, settings.current());
        settings.onChange(rules::settings);
        server.getPluginManager().registerEvents(rules, context.plugin());

        ManhuntSpectators spectators = new ManhuntSpectators(context.plugin(), liveManhunt,
                settings.current());
        settings.onChange(spectators::settings);

        ManhuntAchievements manhuntAchievements = new ManhuntAchievements(context.core().achievements());
        manhuntAchievements.defineAll();

        // Every hunt that finishes, ever — the one thing manhunt-roadmap-to-1-0 named as still
        // missing beyond the four run-lifecycle areas. Its own database, like the tracking compass'
        // portals need nothing from Core's own core.db/audit.db — see HuntHistory's own javadoc.
        HuntHistory history = new HuntHistory(context.core().databases().of("manhunt-history", HuntHistory.SCHEMA));
        // Snapshotted at the moment a hunt actually starts, not read again at onFinished: the roster
        // is frozen for the whole run (see ManhuntTeams/ManhuntService.isRunning), so the two moments
        // agree, and reading it here means onFinished never has to ask "who was still a Runner" of a
        // roster that a settings change or a fresh join could have moved on by the time it fires.
        AtomicReference<RunStart> currentRun = new AtomicReference<>();

        // Both hooks take exactly one caller each (see ManhuntService.onStart) — stacking two concerns
        // behind the same moment is this wiring class' job, not the service's.
        // Each concern on its own, so the first one to throw cannot abandon the rest — see step().
        // The compass used to be last of five bare statements here, which is exactly how a live hunt
        // started correctly in every visible way and handed out no compasses at all.
        Trouble trouble = (what, broken) -> log.error(broken,
                "A hunt started, but {} failed. The rest of the hunt is unaffected.", what);
        liveManhunt.onStart(roster -> {
            currentRun.set(new RunStart(Instant.now(), Set.copyOf(teams.runners()), Set.copyOf(teams.hunters())));
            step("awarding the first-hunt achievement", () -> manhuntAchievements.awardFirstHunt(roster), trouble);
            step("resetting the death counters", deaths::reset, trouble);
            step("borrowing the hunt's gamerules", rules::arm, trouble);
            step("starting the narrator", narrator::arm, trouble);
            step("handing out the tracking compasses", () -> tracker.armFor(roster), trouble);
        });
        // Guarded one by one for a sharper reason than the start hook's: every line below the first
        // is a hand-back. An awardWin that threw used to take rules.disarm() with it, and a hunt's
        // borrowed gamerules would then never be given back — the server left permanently altered by
        // a match that had already ended.
        Trouble afterwards = (what, broken) -> log.error(broken,
                "A hunt finished, but {} failed. Everything else about the ending still ran.", what);
        liveManhunt.onFinished((everybody, outcome) -> {
            step("awarding the win", () -> manhuntAchievements.awardWin(everybody, teams, outcome.reason()),
                    afterwards);
            RunStart started = currentRun.getAndSet(null);
            if (started != null) {
                // Off the server thread, like every other database write in this reactor — see
                // Database.write's own note on why a write on the thread running the world is only
                // ever reported, never blocked.
                Scheduling.async(context.plugin(), () ->
                        history.record(started.startedAt(), started.runners(), started.hunters(), outcome));
            }
            step("stopping the narrator", narrator::disarm, afterwards);
            step("taking the tracking compasses back", tracker::disarm, afterwards);
            step("handing the hunt's gamerules back", rules::disarm, afterwards);
            step("releasing the spectators", spectators::releaseAll, afterwards);
            step("sending everybody home", () -> endOfRun.finish(everybody), afterwards);
        });

        this.services = new ManhuntServices(
                context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, settings,
                liveManhunt, chaos, whitelist, manhuntAchievements, lobbyListener, tracker, deaths, spectators,
                history,
                new LiveScreens());

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See ManhuntCommands.
        ManhuntCommands.ready(services);

        // A button on the speedrun compass' own screen. Offered from this side because the
        // dependency only runs this way — see SpeedrunCompanions for the whole argument. Withdrawn
        // again in disable(), which is the half that actually matters: a stale entry would hand the
        // next clicker a door into a module that is no longer loaded.
        SpeedrunCompanions.offer(new SpeedrunCompanions.Companion(
                "manhunt", "Manhunt", "<gray>Runners against Hunters, on this same engine.",
                Material.TARGET,
                (viewer, parent) -> new ManhuntLobbyMenu(services, viewer, parent).open()));

        log.info("Manhunt is up: {} Runner(s), {} Hunter(s).",
                teams.runners().size(), teams.hunters().size());
    }

    /** What {@link HuntHistory#record} needs from the moment a hunt began — see the field's own note. */
    private record RunStart(Instant startedAt, Set<UUID> runners, Set<UUID> hunters) {
    }

    /** Told what failed and what threw — {@code log::error} in practice, a collector in the tests. */
    @FunctionalInterface
    interface Trouble {
        void with(String what, Throwable thrown);
    }

    /**
     * Runs one concern of a hunt starting or ending, and contains its failure.
     *
     * <h2>Why this exists</h2>
     * {@link ManhuntService#onStart} takes exactly one hook on purpose, so this class stacks several
     * independent concerns behind it. Written as bare statements in one lambda, the first of them to
     * throw silently abandoned every one after it — and the tracking compass was last, so a hunt
     * could start correctly in every visible way (the countdown, the boss bar and the clock all
     * happen <em>before</em> the hook fires) and hand out no compasses at all, with nothing in the
     * log pointing anywhere near the compass. That is a real report, not a hypothetical.
     *
     * <p>These concerns are genuinely independent: a missing achievement store is no reason for the
     * Hunters to go without a compass, and a gamerule that could not be borrowed is no reason for the
     * narrator to stay silent. Each is therefore run on its own and its failure is named in the log —
     * the same "one listener must not stop another's" rule {@code SettingsStore.publish} already
     * applies to settings listeners.
     *
     * <p>{@link Throwable}, not {@link RuntimeException}: the realistic failure is an API that moved
     * between the Paper this compiles against and the one a server runs — {@code GameRule} is
     * deprecated-for-removal on 26.2 — and that arrives as an {@link Error} a narrower guard would
     * let straight through. {@code ModuleCommands.canUse} catches {@link Throwable} for the same
     * reason.
     */
    static void step(String what, Runnable concern, Trouble trouble) {
        try {
            concern.run();
        } catch (Throwable broken) {
            trouble.with(what, broken);
        }
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist —
     * an inner class rather than a supplier-holding record, so it reads {@link #services} lazily off
     * the enclosing module at click time instead of needing to be handed a reference to a
     * {@link ManhuntServices} that has not finished being built yet when this is constructed.
     */
    private final class LiveScreens implements IManhuntScreensOpener {

        @Override
        public void lobby(Player viewer) {
            new ManhuntLobbyMenu(services, viewer, null).open();
        }

        @Override
        public void chaos(Player viewer) {
            new ManhuntChaosMenu(services, viewer, null).open();
        }

        @Override
        public void achievements(Player viewer) {
            new ManhuntAchievementsMenu(services, viewer, null).open();
        }

        @Override
        public void options(Player viewer) {
            new ManhuntOptionsMenu(services, viewer, null).open();
        }

        @Override
        public void tracker(Player viewer) {
            new ManhuntTrackerMenu(services, viewer, null).open();
        }

        @Override
        public void field(Player viewer) {
            new ManhuntFieldMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return ManhuntCommands.declared();
    }

    @Override
    public void disable() {
        ManhuntCommands.stopped();
        SpeedrunCompanions.withdraw("manhunt");
        if (manhunt != null) {
            // shutdown() finishes the session, which runs onFinished — the rules are handed back and
            // the watchers released there. This only covers the case of there being no run at all.
            manhunt.shutdown();
        }
    }
}
