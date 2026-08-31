package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.screen.ManhuntAchievementsMenu;
import de.raindancer.modules.manhunt.screen.ManhuntChaosMenu;
import de.raindancer.modules.manhunt.screen.ManhuntLobbyMenu;
import de.raindancer.modules.manhunt.screen.ManhuntOptionsMenu;
import de.raindancer.modules.manhunt.service.ChaosService;
import de.raindancer.modules.manhunt.service.ManhuntAchievements;
import de.raindancer.modules.manhunt.service.ManhuntLobbyBox;
import de.raindancer.modules.manhunt.service.ManhuntLobbyListener;
import de.raindancer.modules.manhunt.service.ManhuntService;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

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

    private static final ModuleInfo INFO = ModuleInfo.of("manhunt", "Manhunt", "1.0.0")
            .describedAs("Runners against Hunters on top of speedrun-module's engine — a win "
                    + "condition per side, a real server whitelist a Runner can open and close, "
                    + "and live chaos actions a host can throw at a running match.")
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

        ManhuntAchievements manhuntAchievements = new ManhuntAchievements(context.core().achievements());
        manhuntAchievements.defineAll();
        liveManhunt.onStart(manhuntAchievements::awardFirstHunt);
        liveManhunt.onFinished((everybody, outcome) ->
                manhuntAchievements.awardWin(everybody, teams, outcome.reason()));

        this.services = new ManhuntServices(
                context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, settings,
                liveManhunt, chaos, whitelist, manhuntAchievements, lobbyListener,
                new LiveScreens());

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See ManhuntCommands.
        ManhuntCommands.ready(services);

        log.info("Manhunt is up: {} Runner(s), {} Hunter(s).",
                teams.runners().size(), teams.hunters().size());
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
    }

    @Override
    public List<ModuleCommand> commands() {
        return ManhuntCommands.declared();
    }

    @Override
    public void disable() {
        ManhuntCommands.stopped();
        if (manhunt != null) {
            manhunt.shutdown();
        }
    }
}
