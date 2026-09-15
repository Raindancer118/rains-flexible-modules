package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.manhunt.mode.ManhuntMode;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.screen.ManhuntSidesMenu;
import de.raindancer.modules.manhunt.service.Eliminations;
import de.raindancer.modules.manhunt.service.HuntersByDefaultListener;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.service.WhitelistVips;
import de.raindancer.modules.manhunt.service.SpectatorRestoreListener;
import de.raindancer.modules.manhunt.tracker.PortalMemory;
import de.raindancer.modules.manhunt.tracker.TrackerCompass;
import de.raindancer.modules.manhunt.tracker.TrackerCompassService;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import de.raindancer.modules.speedrun.SpeedrunModes;
import org.bukkit.Server;

import java.util.List;

/**
 * "RainsManhunt", as a module.
 *
 * <h2>What this module is now, and what it used to be</h2>
 * It is a {@link de.raindancer.modules.speedrun.SpeedrunMode} — one game offered to the speedrun
 * lobby, which then plays it in its own world, with its own countdown, its own clock and its own
 * reset. It used to be a second lobby standing beside that one with a copy of each of those, and
 * every visible bug of the last version came out of the two disagreeing: the speedrun lobby's compass
 * and start block still in everybody's hands as a hunt began, two plugins both declaring a
 * {@code world-name}, a hunt's own start teleport announced as a Runner reaching the Overworld.
 *
 * <h2>What is left here</h2>
 * The two sides, the tracking compass, what being caught costs, and the server's own door.
 */
public final class ManhuntModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("manhunt", "Manhunt", "0.13.0")
            .describedAs("Runners against Hunters, played in the speedrun lobby: the lobby's own "
                    + "goal is what the Runners race for, every Hunter carries a compass that "
                    + "follows a Runner through the portal they took, a caught Runner is out for "
                    + "good, and a Runner can open and close the server's door around a hunt.")
            .by("Raindancer118");

    private ManhuntMode mode;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    /** The oldest RainsSpeedrun that has the game-mode seam this module hangs on. */
    static final String SPEEDRUN_NEEDED = "1.11.0";

    @Override
    public void enable(ModuleContext context) {
        LogChannel log = context.log();
        Server server = context.plugin().getServer();

        // First, before any line that touches speedrun-module's newer classes — see
        // requireCurrentSpeedrun for why this cannot be left to the class loader.
        requireCurrentSpeedrun(server);

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

        // The sides are frozen for exactly as long as a hunt is under way — asked of the mode rather
        // than kept as a second flag here, which is the same "one fact, one owner" rule the hunt's own
        // roster follows.
        ManhuntTeams teams = new ManhuntTeams(() -> mode != null && mode.isRunning());
        Eliminations eliminations = new Eliminations(context.plugin());
        // The few people a clear never sweeps up and a close always lets in — kept on disk beside the
        // module's own settings, because a list of players is not a settings field. See WhitelistVips.
        ManhuntWhitelistService whitelist = new ManhuntWhitelistService(server,
                new WhitelistVips(context.dataFolder().resolve("whitelist-vips.yml")));

        PortalMemory portals = new PortalMemory();
        TrackerCompass compass = new TrackerCompass(settings.current(), portals);
        settings.onChange(compass::settings);
        TrackerCompassService tracker = new TrackerCompassService(context.plugin(),
                () -> mode == null ? java.util.Optional.empty() : mode.current(),
                compass, portals, context.core().messages(), context.core().actionBars(),
                settings.current());
        settings.onChange(tracker::settings);

        ManhuntServices[] holder = new ManhuntServices[1];
        ManhuntMode liveMode = new ManhuntMode(context.plugin(), teams, eliminations, tracker, portals,
                whitelist, context.core().messages(), settings::current,
                // The sides page, opened from the speedrun compass' own menu. A lambda rather than a
                // reference to the services, which are built a line below this and cannot be handed
                // in before they exist.
                (viewer, parent) -> new ManhuntSidesMenu(holder[0], viewer, parent).open());
        this.mode = liveMode;

        ManhuntServices services = new ManhuntServices(context.core().messages(),
                context.chat().brand(), settings, teams, liveMode, whitelist,
                new ManhuntServices.Screens() {
                    @Override
                    public void sides(org.bukkit.entity.Player viewer) {
                        new ManhuntSidesMenu(holder[0], viewer, null).open();
                    }

                    @Override
                    public void confirm(org.bukkit.entity.Player viewer, String question,
                                        List<String> consequences, Runnable onYes) {
                        // Core's own page, rather than a fourth copy of "are you sure?" — see
                        // ConfirmMenu's javadoc for why no plugin writes its own any more. The
                        // closing line is this module's, because nothing here is undone by saying no.
                        new ConfirmMenu(viewer, context.chat().brand(), null, question, consequences,
                                "<dark_gray>The hunt carries on either way.", onYes).open();
                    }
                });
        holder[0] = services;

        // With the Runners hand-picked, everybody who has not been named is hunting — said up front
        // rather than at the start whistle. See HuntersByDefaultListener.
        HuntersByDefaultListener huntersByDefault = new HuntersByDefaultListener(
                settings::current, teams, () -> mode != null && mode.isRunning());
        context.listener(huntersByDefault);
        huntersByDefault.sweep(server);
        // And again the moment a host turns the setting off with a lobby full of people who never
        // picked anything.
        settings.onChange(fresh -> huntersByDefault.sweep(server));

        // Registered for the life of the module, not of a hunt: its whole job is somebody whose hunt
        // no longer exists. Everything scoped to one hunt is registered through SpeedrunRun instead —
        // see ManhuntMode.onStart.
        context.listener(new SpectatorRestoreListener(
                () -> mode == null ? java.util.Optional.empty() : mode.current(), eliminations));

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See ManhuntCommands.
        ManhuntCommands.ready(services);

        // The lobby cannot name this module — the dependency only runs this way. See SpeedrunModes.
        SpeedrunModes.offer(liveMode);

        log.info("Manhunt is up, as a game the speedrun lobby can play: {} Runner(s) waiting.",
                teams.runners().size());
    }

    private static void requireCurrentSpeedrun(Server server) {
        Class<?> modes;
        try {
            // By name, not by class literal: a literal of a class that is not there fails to link
            // right here, with the very error this method exists to replace.
            modes = Class.forName("de.raindancer.modules.speedrun.SpeedrunModes");
        } catch (ClassNotFoundException missing) {
            modes = null;
        }
        var speedrun = server.getPluginManager().getPlugin("RainsSpeedrun");
        requireCurrentSpeedrun(modes,
                speedrun == null ? null : speedrun.getPluginMeta().getVersion());
    }

    /**
     * Refuses to start against a RainsSpeedrun too old for this module, and says which jar to replace.
     *
     * <h2>Why this has to be checked by hand</h2>
     * A Paper descriptor can require RainsSpeedrun, but not a version of it, so an older jar loads
     * without complaint and this module dies the first time it touches a class that is not there —
     * reported live once already as "IllegalAccessError: failed to access class …SpeedrunTimerDisplay"
     * with RainsSpeedrun 1.9.0 installed. That error is accurate and useless: it names a class, where
     * the person reading it needs a jar.
     *
     * <p>Checked by capability rather than by comparing version strings: the question is whether the
     * class this module needs is reachable, and the version is only for the message. A speedrun-module
     * shaded into a bundle under another plugin name still passes, because the class is what counts.
     */
    static void requireCurrentSpeedrun(Class<?> speedrunModes, String foundVersion) {
        if (speedrunModes != null && java.lang.reflect.Modifier.isPublic(speedrunModes.getModifiers())) {
            return;
        }
        throw new IllegalStateException("Manhunt needs RainsSpeedrun " + SPEEDRUN_NEEDED + " or newer, "
                + (foundVersion == null ? "and the installed one is older"
                        : "but RainsSpeedrun " + foundVersion + " is installed")
                + " — replace the RainsSpeedrun jar in plugins/ and restart.");
    }

    @Override
    public List<ModuleCommand> commands() {
        return ManhuntCommands.declared();
    }

    @Override
    public void disable() {
        ManhuntCommands.stopped();
        // Withdrawn before anything else: a mode left on the shelf hands the next start to a plugin
        // that is no longer loaded.
        SpeedrunModes.withdraw(ManhuntMode.ID);
        if (mode != null) {
            // A hunt that outlives its plugin leaves its Runners spectating for good. The compasses
            // and the whitelist go back the same way they would at any other ending.
            mode.forget();
        }
    }
}
