package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.core.ui.profile.ProfileExtensions;
import de.raindancer.core.world.visual.Navigator;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.manhunt.hud.HuntTicker;
import de.raindancer.modules.speedrun.manhunt.screen.Pages;
import de.raindancer.modules.speedrun.manhunt.setup.HuntDesk;
import de.raindancer.modules.speedrun.manhunt.setup.ManhuntChecks;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunLobby;
import de.raindancer.modules.speedrun.SpeedrunToolkit;
import de.raindancer.modules.speedrun.manhunt.stats.HuntChronicle;
import de.raindancer.modules.speedrun.manhunt.stats.HuntRecorder;
import de.raindancer.modules.speedrun.manhunt.stats.StatsStore;
import de.raindancer.modules.speedrun.manhunt.screen.ManhuntTrackerMenu;
import de.raindancer.modules.speedrun.manhunt.screen.StructureChoiceMenu;
import de.raindancer.modules.speedrun.manhunt.screen.TrailProfileButton;
import de.raindancer.modules.speedrun.manhunt.service.Eliminations;
import de.raindancer.modules.speedrun.manhunt.service.HuntersByDefaultListener;
import de.raindancer.modules.speedrun.manhunt.service.HuntersFistsOnly;
import de.raindancer.modules.speedrun.manhunt.service.ManhuntTeamChannel;
import de.raindancer.modules.speedrun.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.speedrun.manhunt.service.PositionShare;
import de.raindancer.modules.speedrun.manhunt.service.SpectatorRestoreListener;
import de.raindancer.modules.speedrun.manhunt.service.WhitelistVips;
import de.raindancer.modules.speedrun.manhunt.tracker.CompassHandout;
import de.raindancer.modules.speedrun.manhunt.tracker.CompassKeeper;
import de.raindancer.modules.speedrun.manhunt.tracker.HuntCompasses;
import de.raindancer.modules.speedrun.manhunt.tracker.PortalMemory;
import de.raindancer.modules.speedrun.manhunt.tracker.StructureChoices;
import de.raindancer.modules.speedrun.manhunt.tracker.StructureCompassListener;
import de.raindancer.modules.speedrun.manhunt.tracker.StructureCompassService;
import de.raindancer.modules.speedrun.manhunt.tracker.TeamCompassService;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompassService;
import de.raindancer.modules.speedrun.manhunt.util.PermissionNodes;
import de.raindancer.modules.speedrun.SpeedrunModes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Manhunt, the game built into the speedrun lobby — what used to be the RainsManhunt plugin.
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
public final class ManhuntGame {

    private ManhuntMode mode;
    private RainsCore core;
    private HuntersFistsOnly fistsOnly;
    private PositionShare navigation;
    private TrailProfileButton trailButton;
    private ManhuntTeamChannel teamChannel;

    /**
     * Called by {@code SpeedrunModule.enable} once the lobby is up. Its wording is part of the
     * speedrun module's one messages file, already defined by then.
     */
    public void enable(ModuleContext context, SpeedrunLobby lobby) {
        LogChannel log = context.log();
        Server server = context.plugin().getServer();

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        // A file of its own beside the lobby's config.yml, so no key of one can ever read as the
        // other's; its pages sit under the lobby's own in /settings (speedrun/manhunt/…).
        SettingsStore<ManhuntSettings> settings = context.core().settingsFor(
                SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS),
                context.dataFolder().resolve(SETTINGS_FILE));
        context.closeWith(() -> context.core().forgetSettings(settings));

        // The sides are frozen for exactly as long as a hunt is under way — asked of the mode rather
        // than kept as a second flag here, which is the same "one fact, one owner" rule the hunt's own
        // roster follows.
        ManhuntTeams teams = new ManhuntTeams(() -> mode != null && mode.isRunning());
        Eliminations eliminations = new Eliminations(context.plugin());
        // The few people a clear never sweeps up and a close always lets in — kept on disk beside the
        // module's own settings, because a list of players is not a settings field. See WhitelistVips.
        ManhuntWhitelistService whitelist = new ManhuntWhitelistService(server,
                new WhitelistVips(context.dataFolder().resolve("whitelist-vips.yml")),
                context.dataFolder().resolve("whitelist-state.yml"));
        // No hunt survives a restart, so a door a hunt shut — and never got to open, because the
        // server stopped or crashed under it — is opened now.
        if (whitelist.reopenAfterHunt()) {
            log.info("The whitelist a hunt had closed was left on by a restart; it is open again.");
        }

        // Everything below reads the hunt through the mode, which is built last — it needs them.
        Supplier<Optional<Hunt>> liveHunt = () -> mode == null ? Optional.empty() : mode.current();

        PortalMemory portals = new PortalMemory();
        TrackerCompass compass = new TrackerCompass(settings.current(), portals);
        settings.onChange(compass::settings);
        TrackerCompassService tracker = new TrackerCompassService(context.plugin(), liveHunt,
                compass, portals, context.core().messages(), context.core().actionBars(),
                settings.current());
        settings.onChange(tracker::settings);
        tracker.pickerScreen(viewer -> new ManhuntTrackerMenu(tracker::targetsFor, tracker::pick,
                context.chat().brand(), context.core().messages().raw("manhunt.tracker.picker-title"),
                viewer).open());
        // The second compass, pointing at your own side — see TeamCompassService.
        TeamCompassService teamCompass = new TeamCompassService(context.plugin(), liveHunt, compass,
                context.core().messages(), context.core().actionBars(), settings.current());
        settings.onChange(teamCompass::settings);
        teamCompass.pickerScreen(viewer -> new ManhuntTrackerMenu(teamCompass::targetsFor,
                teamCompass::pick, context.chat().brand(),
                context.core().messages().raw("manhunt.team-compass.picker-title"), viewer).open());
        // The Runners' structure compass — see StructureCompassService.
        StructureCompassService structures = new StructureCompassService(context.plugin(), liveHunt,
                context.core().messages(), settings::current, StructureCompassService.worldSearch());
        structures.chooserScreen(viewer -> new StructureChoiceMenu(structures, context.chat().brand(),
                viewer).open());
        warnAboutUnknownStructures(log);
        context.listener(new StructureCompassListener(structures));
        HuntCompasses compasses = new HuntCompasses(context.plugin(), liveHunt, tracker, teamCompass,
                structures);
        context.listener(compasses);
        context.listener(new CompassKeeper(compasses::isOurs));
        // After the three services' own listeners, so they already hold the new settings.
        settings.onChange(fresh -> compasses.settingsChanged());

        ManhuntServices[] holder = new ManhuntServices[1];
        Pages pages = new Pages(context.plugin(), () -> holder[0], context.chat(),
                () -> context.core().settingsNavigation(), context.core().prompts());
        ManhuntMode liveMode = new ManhuntMode(context.plugin(), teams, eliminations, compasses, portals,
                whitelist, context.core().messages(), settings::current,
                // The hub, opened from the speedrun compass' own menu — the same page /manhunt opens.
                (viewer, parent) -> pages.open(viewer, ManhuntServices.Page.HUB, parent));
        this.mode = liveMode;

        // What every hunt leaves behind goes into the lobby's one history; what is Manhunt's alone —
        // the head-start bar, the glow, the Runners' lines on the sidebar — is the ticker's. See HuntChronicle.
        Supplier<SpeedrunHistory> history = () -> lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
        // Manhunt's own stats and hunts from before the merge, once — see ManhuntImport.
        ManhuntImport.run(context.dataFolder(), history.get());
        StatsStore stats = new StatsStore(history, ManhuntMode.ID);
        HuntChronicle chronicle = new HuntChronicle(context.plugin(), settings::current,
                context.core().messages(), context.core().buttons(), System::currentTimeMillis,
                (hunt, record, run, hold, headStart) -> {
                    HuntTicker beat = new HuntTicker(context.plugin(), hunt, record, run.session()::elapsed, hold,
                            headStart, System.currentTimeMillis(), settings::current, context.core().messages(),
                            context.core().bossBars(), runner -> runner.addPotionEffect(new PotionEffect(
                                    PotionEffectType.GLOWING, settings.current().glowSecondsClamped() * 20, 0,
                                    false, false)));
                    run.hudLines(beat::linesFor);
                    return beat;
                },
                (hunt, run, keeping) -> run.listen(new HuntRecorder(hunt, keeping::portal)),
                lobby::lastRun, run -> Optional.ofNullable(history.get()).map(h -> h.numberOf(run)).orElse(0));
        liveMode.watch(chronicle);
        HuntDesk desk = new HuntDesk(() -> present(server, context.core()), id -> nameOf(server, id), settings,
                () -> context.core().settingsNavigation().registry(), teams, liveMode::isRunning,
                whitelist::isClosed, stats, ManhuntGame::advancementExists, new Random());
        ManhuntChecks checks = new ManhuntChecks(ManhuntChecks.of(desk), settings,
                player -> pages.open(player, ManhuntServices.Page.SIDES, null));
        liveMode.preflightBy(checks::checks);
        liveMode.setupQuestionsBy(checks::questions);

        // /manhunt here: everybody told where you are, the coordinates a button that walks the clicker
        // there with Core's Navigator — see PositionShare.
        navigation = new PositionShare(context.plugin(), context.core().buttons(), context.core().messages(),
                new Navigator(context.plugin(), context.core().actionBars(), context.core().messages()),
                settings::current);
        trailButton = new TrailProfileButton(settings::current, context.core().messages());
        ProfileExtensions.register(trailButton);
        // Team chat — /chat team — is the chat plugin's to route; this only says who is on your side.
        teamChannel = new ManhuntTeamChannel(teams, liveHunt);
        ChatChannels.register(teamChannel);

        ManhuntServices services = new ManhuntServices(context.core().messages(),
                context.chat().brand(), settings, teams, liveMode, whitelist, pages, navigation,
                new CompassHandout(context.plugin(), liveHunt, compasses, context.core().messages()),
                desk, chronicle, stats, () -> lobby);
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
        SpectatorRestoreListener spectators = new SpectatorRestoreListener(liveHunt, eliminations);
        context.listener(spectators);
        spectators.sweep(server.getOnlinePlayers());

        // Hunters only punching each other is one of Core's combat rules, not a damage listener of
        // our own — Core already traces arrows and pets back to a person and says how a hit landed.
        // The refusal is Core's combat.fists-only, said in Manhunt's own words for Manhunt only.
        core = context.core();
        fistsOnly = new HuntersFistsOnly(liveHunt, settings::current);
        core.combat().alsoAsk(ManhuntMode.ID, fistsOnly);
        core.messages().overrideFor(ManhuntMode.ID, "combat.fists-only", "manhunt.teammate-hit");

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See ManhuntCommands.
        ManhuntCommands.ready(services);

        // The lobby cannot name this module — the dependency only runs this way. See SpeedrunModes.
        SpeedrunModes.offer(liveMode);

        log.info("Manhunt is up, as a game the speedrun lobby can play: {} Runner(s) waiting.",
                teams.runners().size());
    }

    /** Everybody a start would sweep up: who is in the speedrun lobby's world — everybody online without one. */
    private static java.util.Set<UUID> present(Server server, RainsCore core) {
        String world = core.settingsNavigation().registry().display("speedrun:world-name").trim();
        World lobby = world.isEmpty() ? null : server.getWorld(world);
        java.util.Set<UUID> here = new java.util.LinkedHashSet<>();
        for (Player player : lobby != null ? lobby.getPlayers() : List.copyOf(server.getOnlinePlayers())) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                here.add(player.getUniqueId());
            }
        }
        return here;
    }

    private static String nameOf(Server server, UUID id) {
        Player online = server.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String name = server.getOfflinePlayer(id).getName();
        return name == null ? "somebody" : name;
    }

    private static boolean advancementExists(String key) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        return parsed != null && Bukkit.getAdvancement(parsed) != null;
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? entity.getHealth() : max.getValue();
    }

    /** A structure id this server does not know would silently find nothing — said at startup. */
    private static void warnAboutUnknownStructures(LogChannel log) {
        for (StructureChoices.Choice choice : StructureChoices.all()) {
            for (String key : choice.structureKeys()) {
                if (Registry.STRUCTURE.get(NamespacedKey.minecraft(key)) == null) {
                    log.warn("The structure compass knows '{}' but this server has no such structure.", key);
                }
            }
            if (Material.matchMaterial(choice.icon()) == null) {
                log.warn("The structure compass' icon '{}' is not an item on this server.", choice.icon());
            }
        }
    }

    /** Manhunt's own settings file in the speedrun module's folder. */
    public static final String SETTINGS_FILE = "manhunt.yml";

    /** {@code /manhunt} and {@code /whitelist}, declared at bootstrap with the lobby's own. */
    public static List<ModuleCommand> commands() {
        return ManhuntCommands.declared();
    }

    public void disable() {
        ManhuntCommands.stopped();
        // Withdrawn before anything else: a mode left on the shelf hands the next start to a plugin
        // that is no longer loaded.
        SpeedrunModes.withdraw(ManhuntMode.ID);
        if (teamChannel != null) {
            ChatChannels.unregister(teamChannel);
        }
        if (trailButton != null) {
            ProfileExtensions.unregister(trailButton);
        }
        if (navigation != null) {
            navigation.stopAll();
        }
        if (core != null) {
            core.combat().stopAsking(fistsOnly);
            core.messages().forgetOverridesFor(ManhuntMode.ID);
        }
        if (mode != null) {
            // A hunt that outlives its plugin leaves its Runners spectating for good. The compasses
            // and the whitelist go back the same way they would at any other ending.
            mode.forget();
        }
    }
}
