package de.raindancer.modules.hungergames;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.banner.Banner;
import de.raindancer.core.world.time.Times;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.rules.ConfigurationRules;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.BorderService;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.YamlSessionStore;
import de.raindancer.modules.hungergames.store.BorderPhaseStore;
import de.raindancer.modules.hungergames.store.LegacyConfigImport;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import org.bukkit.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * A Hunger Games tournament, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code TheHungerGames}, a plugin of its own. Hosted inside
 * another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What this is a port of, and what was left behind</h2>
 * The plugin this replaces carried a great deal that was never about the Hunger Games: its own menu
 * framework, its own settings system with a hand-written 835-line catalogue, its own message table, its own
 * sound and effect services, its own name resolver, its own loot manager, its own custom items, its own chat
 * input handler and its own write-to-a-temporary-then-move. Every one of those is RainsCore's here, and none
 * of them was ported — {@code ReuseTest} fails the build if any grows back.
 *
 * <p>What is left is the game: the phases a round goes through, who is in it, who is on whose team, where the
 * border is, what the Capitol drops, and who won. That is the part worth having written down once.
 *
 * <h2>Why the arena needs WorldEdit</h2>
 * The cornucopia, the platforms and the starting tubes are schematics, and reading a Sponge {@code .schem} is
 * somebody else's solved problem. WorldEdit is {@code provided}, exactly like paper-api and RainsCore, and the
 * standalone descriptor requires it: a plugin that comes up fine and only fails at {@code /init}, with forty
 * people already waiting, is worse than one that refuses to load and says why.
 */
public final class HungerGamesModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("hungergames", "Hunger Games", "2.0.0")
            .describedAs("A tournament: tributes, teams, a shrinking border, and the screens a "
                    + "gamemaster runs a round from")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<HungerGamesSettings> settings;
    private GameSession session;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw away
        // Core's own wording and every other module's with it.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the root of its
        // own jar and join-classpath puts it on this module's classpath, so a root lookup is a race between
        // two files with the same name.
        //
        // Signed with this module's own brand, so its sentences say Hunger Games. Without the signature the
        // section is unowned and wears whichever module plugin started last — on the live server one module's
        // line arrived branded "Moderation »".
        context.core().messages().defineFrom(
                HungerGamesModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered node resolves to "operators only", which would quietly make
        // every gamemaster an admin's problem on the one night it matters.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        // Before the banner, so an upgrading server sees what came across *and* what did not, above the
        // splash rather than scrolled off under it.
        importAnOldConfig(context);

        wire(context);

        announce(context, registered);
        auditTheConfiguration(context);
    }

    /**
     * Builds what the commands need and hands it to {@link HungerGamesCommands}.
     *
     * <p>The commands were built during bootstrap, before any of this existed, and have been holding a
     * supplier ever since. This is the moment it starts answering — and until it does, the host's own guard
     * answers every one of them with a red line rather than a {@link NullPointerException}.
     *
     * <h2>What is wired here and what is not</h2>
     * The session, the round control and the deathmatch: exactly what a <em>command</em> needs. The other
     * thirty-odd services are built by, and belong to, the screens and listeners that use them — a module
     * that constructed all of them here would make every command implicitly depend on all of them, which is
     * the god object this design exists to avoid.
     *
     * <p>The three arena stages are {@link GameControlService#notYetAvailable()}. That is not a stub standing
     * in for finished work: pasting the arena needs WorldEdit and the schematic wave, and a stage wired to
     * something that pretends to succeed would report a built arena that is not there. As it stands
     * {@code /init} refuses with a reason, which is the honest answer until the runner exists.
     */
    private void wire(ModuleContext context) {
        RoundLogService roundLog = new RoundLogService(
                context.dataFolder().resolve("rounds"),
                uuid -> session == null ? uuid.toString()
                        : session.participants().nameOf(uuid).orElse(uuid.toString()),
                TeamId::value,
                log);

        session = new GameSession(
                TeamRules::defaults,
                roundLog,
                new YamlSessionStore(context.dataFolder().resolve("session.yml")),
                GameClock.system(),
                new Random());

        GameControlService control = new GameControlService(session, actor -> false,
                GameControlService.notYetAvailable(),
                GameControlService.notYetAvailable(),
                GameControlService.notYetAvailable());
        control.settings(settings.current());

        BorderService border = new BorderService(session, new VirtualTime(), new NoBorderYet());
        border.settings(settings.current());

        DeathmatchService deathmatch = new DeathmatchService(session, border, unused -> {
            // Teleporting everybody to the middle belongs to the arena wave, which knows where the middle
            // is. Empty and said out loud rather than guessed at: a deathmatch that teleported tributes to
            // a coordinate this class invented would drop forty people into terrain.
        });
        deathmatch.settings(settings.current());

        HungerGamesServices built = new HungerGamesServices(
                context.plugin(), context.plugin().getServer(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                session, control, null, deathmatch,
                settings::current, new NoScreensYet(log));

        HungerGamesCommands.ready(built);
        // A reload has to reach the services that read settings, or they keep yesterday's numbers for the
        // rest of the tournament — the exact failure IHungerGamesService.settings exists to prevent.
        settings.onChange(now -> {
            control.settings(now);
            border.settings(now);
            deathmatch.settings(now);
        });
    }

    /**
     * The splash.
     *
     * <p>Core's {@link Banner}, in gold rather than the house violet. Not decoration for its own sake: this
     * module's every screen, icon and announcement is gold already, and the one place on the server that
     * looked like a different plugin was its own startup line. {@code Banner.in(...)} exists for exactly
     * this and was added to Core rather than to a copy here — a gradient is six hex values that have to step
     * evenly, and a plugin picking six golds by eye gets stripes.
     *
     * <p>The facts are the ones somebody actually checks after a restart: how long a round is, how many
     * tributes may play, and whether the HTTP API opened a socket. Nothing that needs a running round, since
     * at this point there is not one.
     */
    private void announce(ModuleContext context, int permissionsAdded) {
        HungerGamesSettings now = settings.current();
        Banner banner = Banner.of("Hunger Games", "tributes, teams and a border that closes")
                .in(Banner.GOLD)
                .version(INFO.version())
                .by(INFO.author())
                .fact("Round", Times.describe(now.roundDuration()))
                .fact("Countdown", now.countdown() + "s")
                .fact("Tributes", GameControlService.MIN_PLAYERS + "–" + GameControlService.MAX_PLAYERS)
                .fact("Border", now.borderInitialSize() + " blocks, "
                        + now.borderEdgeSpeed() + " b/s at most");

        if (permissionsAdded > 0) {
            banner.fact("Permissions", permissionsAdded + " registered");
        }
        if (now.apiEnabled()) {
            banner.fact("HTTP API", now.apiBindAddress() + ":" + now.apiPort());
        }
        banner.print(context.plugin().getComponentLogger());
    }

    /**
     * Imports an old standalone {@code config.yml} if one has been left for it, and says what happened.
     *
     * <p>Triggered by the file being <em>there</em> rather than by a command, because the moment somebody
     * needs this is the moment they have just swapped the jar and restarted — and a migration they have to
     * know to ask for is one they find out about after the tournament.
     *
     * <p>The file is renamed rather than deleted once read, which is what stops it being imported again on
     * every boot and overwriting whatever has been changed since. Renamed and not consumed: an import that
     * ate its own input cannot be checked afterwards, and the first thing anybody does with a migration is
     * check it.
     */
    private void importAnOldConfig(ModuleContext context) {
        Path waiting = context.dataFolder().resolve(LegacyConfigImport.FILE_NAME);
        if (!Files.isRegularFile(waiting)) {
            return;
        }
        log.info("Found {} — importing settings from the old standalone plugin.",
                LegacyConfigImport.FILE_NAME);

        LegacyConfigImport.Report report;
        try {
            report = LegacyConfigImport.from(waiting, settings);
        } catch (RuntimeException unreadable) {
            log.warn("The old config could not be imported ({}). It has been left where it is; nothing "
                    + "was changed.", unreadable.getMessage());
            return;
        }

        report.lines().forEach(line -> log.warn("  {}", line));

        // The sounds and particles, into Core's registry rather than into this module's settings. Their own
        // pass because they go somewhere else entirely — see LegacyConfigImport.importCues for why they are
        // imported at all rather than reported as "moved, retype them".
        de.raindancer.modules.hungergames.store.LegacyConfigImport.importCues(waiting,
                        cue -> de.raindancer.modules.hungergames.service.HungerGamesCues.names()
                                .contains(cue),
                        (cue, sounds, particles) -> de.raindancer.modules.hungergames.service
                                .HungerGamesCues.rebind(context.core().effects(), cue, sounds, particles))
                .forEach(line -> log.warn("  {}", line));

        Path kept = context.dataFolder().resolve(LegacyConfigImport.FILE_NAME + ".imported");
        try {
            Files.move(waiting, kept);
            log.info("The old file is now {} — delete it when you are happy with the result.",
                    kept.getFileName());
        } catch (IOException couldNotRename) {
            log.warn("The settings were imported but {} could not be renamed ({}). Move or delete it by "
                    + "hand, or it will be imported again on the next restart and overwrite anything you "
                    + "have changed since.", LegacyConfigImport.FILE_NAME, couldNotRename.getMessage());
        }
    }

    /**
     * Says out loud what about this configuration would not work.
     *
     * <p>Here rather than at the point each number is used, because none of these is a fault in a single
     * number: a border that cannot finish shrinking, monster waves scheduled past the end of the round, a
     * deathmatch target below the border's own floor. Every value is individually valid and only the
     * combination is wrong, so the combination has to be looked at somewhere, once, by something that can
     * see all of it.
     *
     * <p>At startup rather than at {@code /init}, because the point is to be read the evening before by
     * whoever is setting the tournament up, not at the moment forty people are waiting. Warnings only —
     * see {@link ConfigurationRules} for why none of this refuses to start.
     */
    private void auditTheConfiguration(ModuleContext context) {
        List<BorderPhaseConfig> phases;
        try {
            phases = new BorderPhaseStore(
                    context.dataFolder().resolve("border-phases.yml"),
                    settings.current().roundDuration()).load();
        } catch (RuntimeException unreadable) {
            // The phase file being unreadable is BorderPhaseStore's own business and it reports it
            // through problems(). Nothing here should turn that into a failure to start.
            log.warn("The border phases could not be read for the startup check: {}",
                    unreadable.getMessage());
            phases = List.of();
        }

        // Reported through Core's SettingsAudit: the heading, the [!]/[?] markers, the worst-first
        // ordering and the closing "none of this stops the plugin" line are the same on every module that
        // does this, and a block that looks different per plugin is one people read differently per plugin.
        new ConfigurationRules().check(settings.current(), phases).report(log, "This Hunger Games configuration");
    }

    @Override
    public List<ModuleCommand> commands() {
        // Asked during bootstrap, possibly more than once, before anything below exists. See
        // HungerGamesCommands for why the handlers hold a supplier rather than anything real.
        return HungerGamesCommands.declared();
    }

    @Override
    public void disable() {
        // So a command typed after the module stops is refused rather than answered by half a tournament.
        HungerGamesCommands.forget();
        // Listeners are unregistered by the context and resources handed to closeWith are closed, in the
        // reverse order they arrived — see ModuleContext. What belongs here is anything that has not reached
        // the disk, and a round that is mid-flight is exactly that: the session is written on every mutation
        // precisely so that this method has nothing left to rescue.
    }

    /**
     * A world border nothing has claimed yet.
     *
     * <p>The real one belongs to the arena wave, which knows which world the arena is in. This reports the
     * border as unmoved and refuses to move it, so a phase that fires before an arena exists does nothing
     * rather than resizing the survival world's border — which is what a helpful default would have done.
     */
    private final class NoBorderYet implements BorderService.WorldBorderTarget {

        @Override
        public double currentSize() {
            return settings.current().borderInitialSize();
        }

        @Override
        public void shrinkOverworld(double targetSize, long ticks) {
            log.warn("The border was asked to close to {} blocks, but no arena world is set up yet.",
                    targetSize);
        }

        @Override
        public void shrinkNether(double targetSize, long ticks) {
            // Silent: the Nether follows the Overworld, and one warning per shrink is enough.
        }

        @Override
        public void resetTo(double size) {
            // Nothing to reset. Silent rather than warned: this is called when a round is cleaned up, and
            // a warning there would fire on every server that never started one.
        }
    }

    /**
     * The screens, before anything has claimed the opener.
     *
     * <p>Every page in {@code screen/} exists and is tested; what does not exist yet is the object that
     * holds the thirty-odd services they need and hands each page its own. Until it does, a command that
     * would open a page says so — rather than opening a half-built one, and rather than throwing in front of
     * whoever typed it.
     */
    private record NoScreensYet(LogChannel log) implements IHungerGamesScreensOpener {

        @Override
        public void admin(org.bukkit.entity.Player viewer) {
            unavailable(viewer, "the admin suite");
        }

        @Override
        public void teams(org.bukkit.entity.Player viewer) {
            unavailable(viewer, "the team page");
        }

        @Override
        public void shop(org.bukkit.entity.Player viewer) {
            unavailable(viewer, "the sponsor shop");
        }

        @Override
        public void spectate(org.bukkit.entity.Player viewer) {
            unavailable(viewer, "the spectator page");
        }

        @Override
        public void borderConflict(org.bukkit.entity.Player viewer) {
            unavailable(viewer, "the border conflict page");
        }

        private void unavailable(org.bukkit.entity.Player viewer, String what) {
            viewer.sendPlainMessage("The Hunger Games screens are not wired up in this build yet ("
                    + what + ").");
            log.warn("{} asked for {}, which has no opener wired yet.", viewer.getName(), what);
        }
    }

    /** The settings as they are right now, for a host that wants to show them. */
    public HungerGamesSettings settings() {
        return settings == null ? HungerGamesSettings.DEFAULTS : settings.current();
    }
}
