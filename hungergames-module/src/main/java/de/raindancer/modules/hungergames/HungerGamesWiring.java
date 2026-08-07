package de.raindancer.modules.hungergames;

import de.raindancer.core.RainsCore;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.spawn.Spawner;
import de.raindancer.core.world.spawn.Spawns;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.hungergames.listener.AdminHotbarListener;
import de.raindancer.modules.hungergames.listener.AnnouncementListener;
import de.raindancer.modules.hungergames.listener.ConnectionListener;
import de.raindancer.modules.hungergames.listener.EliminationListener;
import de.raindancer.modules.hungergames.listener.KrueckauwasserListener;
import de.raindancer.modules.hungergames.listener.LobbyListener;
import de.raindancer.modules.hungergames.listener.MedikitInterruptListener;
import de.raindancer.modules.hungergames.listener.PortalListener;
import de.raindancer.modules.hungergames.listener.StupidnessProtectorListener;
import de.raindancer.modules.hungergames.listener.WinnerFinishListener;
import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.screen.GamemasterMenu;
import de.raindancer.modules.hungergames.screen.ShopMenu;
import de.raindancer.modules.hungergames.service.AnnouncementService;
import de.raindancer.modules.hungergames.service.ArenaBuildService;
import de.raindancer.modules.hungergames.service.ArenaItemService;
import de.raindancer.modules.hungergames.service.ArenaSites;
import de.raindancer.modules.hungergames.service.BorderService;
import de.raindancer.modules.hungergames.service.CombatItemService;
import de.raindancer.modules.hungergames.service.CountdownService;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.HermesBootsService;
import de.raindancer.modules.hungergames.service.GameTimerService;
import de.raindancer.modules.hungergames.service.HungerGamesCues;
import de.raindancer.modules.hungergames.service.MannequinSimService;
import de.raindancer.modules.hungergames.service.MedikitCountdownService;
import de.raindancer.modules.hungergames.service.MobilityItemService;
import de.raindancer.modules.hungergames.service.MonsterWaveService;
import de.raindancer.modules.hungergames.service.OpTrackerService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.service.RoundExpiryService;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.service.SponsorBeaconService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.service.StartupSequenceService;
import de.raindancer.modules.hungergames.service.SupplyDropService;
import de.raindancer.modules.hungergames.service.SurvivalItemService;
import de.raindancer.modules.hungergames.service.TeamPresentationService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.AllGameEvents;
import de.raindancer.modules.hungergames.store.ArenaStore;
import de.raindancer.modules.hungergames.store.BorderPhaseStore;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.GamemasterStore;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import de.raindancer.modules.hungergames.store.LootDefaults;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import de.raindancer.modules.hungergames.store.SponsorShopStore;
import de.raindancer.modules.hungergames.store.TributeRoster;
import de.raindancer.modules.hungergames.store.YamlSessionStore;
import de.raindancer.modules.hungergames.visual.Schematics;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Every service this module has, built once, in the order their dependencies allow.
 *
 * <h2>Why this is its own class</h2>
 * Because it is long, and because none of it is a decision. {@code HungerGamesModule.enable} should read as
 * the five things a module does — settings, wording, permissions, wiring, checks — rather than as three
 * hundred lines of constructor calls with those five buried in it. Everything here is plumbing: what gets
 * handed what, and which Bukkit call sits behind which seam.
 *
 * <h2>The seams, and why there are so many of them</h2>
 * Nearly every service in this module declares a small interface for the part of its job that needs a running
 * server — where a supply drop may land, whether somebody is an operator, how a mannequin is put in the
 * world. That is what makes the deciding half of a tournament testable without booting Paper, and it is the
 * one structural thing the plugin this replaces did not have. The cost is paid here, in one place: this class
 * is where all those interfaces meet their Bukkit implementations.
 *
 * <p>They are lambdas rather than named classes wherever the implementation is a single call. A named class
 * per seam would be forty files whose entire content is one delegation, and the thing worth reading — which
 * Bukkit call answers which question — would be spread across all of them instead of listed here.
 *
 * <h2>The cycle, and how it is resolved</h2>
 * {@link GameSession} needs a {@code GameEvents} in its constructor, and half the things that want to hear
 * about a round need the session in theirs. {@link AllGameEvents} is built empty, handed to the session, and
 * then told about each subscriber as it is built — see {@link AllGameEvents#also}. The alternative is passing
 * ten services a supplier of a session that does not exist yet, which is the same cycle in ten places.
 */
public final class HungerGamesWiring {

    /** The loot table every arena container is filled from, and the one supply drops use. */
    public static final String ARENA_LOOT = "arena";
    public static final String SUPPLY_LOOT = "supply-drop";

    /** How often the round's clock ticks, and how often the timetables are asked whether anything is due. */
    private static final long TICK_PERIOD = 20L;

    private final ModuleContext context;
    private final Plugin plugin;
    private final Server server;
    private final RainsCore core;
    private final LogChannel log;
    private final Brand brand;
    private final SettingsStore<HungerGamesSettings> settingsStore;

    // ---- stores
    private final ArenaStore arenaStore;
    private final RuntimeStore runtimeStore;
    private final BorderPhaseStore borderPhaseStore;
    private final SponsorShopStore shopStore;
    private final TributeRoster roster;
    private final GamemasterStore gamemasterStore;
    private final LootCatalogue lootTables;

    // ---- the round
    private final AllGameEvents events;
    private final GameSession session;
    private final RoundLogService roundLog;
    private final java.util.concurrent.ExecutorService roundLogWriter;
    private final VirtualTime virtualTime;

    // ---- services
    private final ArenaBuildService arena;
    private final ArenaSites sites;
    private final BorderService border;
    private final DeathmatchService deathmatch;
    private final SpectatorService spectators;
    private final de.raindancer.modules.hungergames.service.ChatChannelService chatChannels;
    private final TeamPresentationService presentation;
    private final OpTrackerService opTracker;
    private final AnnouncementService announcements;
    private final SponsorTokenService sponsorTokens;
    private final SponsorBeaconService sponsorBeacons;
    private final SupplyDropService supplyDrops;
    private final MonsterWaveService monsterWaves;
    private final MannequinSimService simulation;

    /** The HTTP admin API's transport — the socket, the key, the routing table. Started in {@link #start()}. */
    private de.raindancer.modules.hungergames.service.HttpApiService httpApi;
    private de.raindancer.modules.hungergames.service.ApiSupport apiSupport;
    private final GameTimerService timer;
    private final RoundExpiryService roundExpiry;
    private final StartupSequenceService startup;
    private final CountdownService countdown;
    private final GameControlService control;
    private final PreflightCheckService preflight;
    private final Gamemasters gamemasters;
    private AdminHotbarListener hotbar;

    // ---- the fourteen custom items, across the four services that define them — see EveryItemIsRegisteredTest
    private final ArenaItemService arenaItems;
    private final CombatItemService combatItems;
    private final MobilityItemService mobilityItems;
    private final HermesBootsService hermesBoots;
    private final SurvivalItemService survivalItems;

    /**
     * Who owns the action bar slot the medikit's count is drawn in.
     *
     * <p>Its own owner rather than the announcements' one: they are two different subsystems writing to the
     * same one line, and sharing a name means either can clear the other's message. Core's {@code ActionBars}
     * exists precisely to arbitrate that, and it cannot if they both claim to be the same thing.
     */
    private static final String MEDIKIT_BAR = "hungergames-medikit";

    /** Who owns the action bar slot Hermes' Boots count down in. See {@link #MEDIKIT_BAR}. */
    private static final String HERMES_BAR = "hungergames-hermes";

    /** Who owns the action bar slot the Fiendfinder's arrow and distance are drawn in. See {@link #MEDIKIT_BAR}. */
    private static final String FIENDFINDER_BAR = "hungergames-fiendfinder";

    /** The medikit's wind-up, and the damage that cancels it. */
    private final MedikitCountdownService medikitCountdown;

    /**
     * Armour lifted out of a holder's slots while the smoke bomb's own invisibility is up, keyed by whoever
     * is holding it. See {@link #hideFully}.
     */
    private final java.util.Map<UUID, ItemStack[]> hiddenArmour = new ConcurrentHashMap<>();

    private volatile List<BorderPhaseConfig> borderPhases = List.of();
    private IHungerGamesScreensOpener screens;

    public HungerGamesWiring(ModuleContext context, SettingsStore<HungerGamesSettings> settingsStore) {
        this.context = context;
        this.plugin = context.plugin();
        this.server = plugin.getServer();
        this.core = context.core();
        this.log = context.log();
        this.brand = context.chat().brand();
        this.settingsStore = settingsStore;

        Path data = context.dataFolder();
        this.arenaStore = new ArenaStore(data.resolve("arena.yml"));
        this.runtimeStore = new RuntimeStore(data.resolve("runtime.yml"));
        this.borderPhaseStore = new BorderPhaseStore(data.resolve("border-phases.yml"),
                settings().roundDuration());
        this.shopStore = new SponsorShopStore(data.resolve("sponsor-shop.yml"));
        // The sign-up sheet, written out empty on first boot so somebody looking for it finds it.
        this.roster = new TributeRoster(data.resolve(TributeRoster.FILE_NAME));
        roster.createIfMissing();
        this.gamemasterStore = new GamemasterStore(data.resolve("gamemasters.yml"));
        this.lootTables = new LootCatalogue(core.lootTables());
        this.borderPhases = loadBorderPhases();

        // ---- the round itself, and the fan-out that everything else hangs off
        this.events = new AllGameEvents();
        // One thread, in order, closed with the module.
        //
        // A round log line is written on every kill, every elimination, every phase change, every drop and
        // every purchase — and it used to be a createDirectories syscall plus an open-append-close on the
        // thread ticking the round, thousands of times an evening. It goes to a queue now.
        //
        // Single-threaded rather than Bukkit's async pool, deliberately: this file is what settles a dispute
        // the next day, and two kills a tick apart must not swap places in it. An executor with one thread is
        // the cheapest thing that promises that.
        this.roundLogWriter = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "HungerGames-round-log");
            thread.setDaemon(true);
            return thread;
        });
        this.roundLog = new RoundLogService(data.resolve("rounds"),
                this::nameOf, TeamId::value, log, java.time.LocalDateTime::now,
                write -> {
                    // Rejected once the module is stopping, which is the ordinary case for the last line or
                    // two of a shutdown. Written here rather than dropped silently.
                    try {
                        roundLogWriter.execute(write);
                    } catch (java.util.concurrent.RejectedExecutionException stopping) {
                        write.run();
                    }
                });
        this.session = new GameSession(() -> TeamRules.from(settings()), events,
                new YamlSessionStore(data.resolve("session.yml")), GameClock.system(), new Random());
        this.virtualTime = new VirtualTime();

        // ---- the arena, which almost everything else asks about
        this.arena = new ArenaBuildService(plugin, session, new Schematics(data, log), arenaStore,
                name -> Optional.ofNullable(server.getWorld(name)), server::getPlayer, arenaTold(), log);
        this.sites = new ArenaSites(arena, server::getWorld);
        this.border = new BorderService(session, virtualTime, arena.borderTarget());

        // ---- speaking
        this.announcements = new AnnouncementService(core.messages(), core.actionBars());
        this.presentation = new TeamPresentationService(session, core.scoreboards(), this::teamHelmet);

        // ---- who is where
        this.spectators = new SpectatorService(session,
                uuid -> Optional.ofNullable(server.getPlayer(uuid)),
                (spectator, target) -> spectator.teleport(target.getLocation()),
                player -> player.setGameMode(GameMode.SPECTATOR));
        this.chatChannels = new de.raindancer.modules.hungergames.service.ChatChannelService(session);
        this.opTracker = new OpTrackerService(session, opAccess(), runtimeStore, roundLog,
                (uuid, message) -> {
                    Player who = server.getPlayer(uuid);
                    if (who != null) {
                        who.sendPlainMessage(message);
                    }
                });
        this.gamemasters = new Gamemasters();

        // ---- the middle of the arena, teleporting into it
        this.deathmatch = new DeathmatchService(session, border, this::gatherEverybodyInTheMiddle);

        // ---- what the Capitol does
        this.sponsorTokens = new SponsorTokenService(session, core.itemFactory(), tokenItem(),
                (player, tokens) -> player.getInventory().addItem(tokens),
                announcements, (category, message) -> roundLog.log(category, message), runtimeStore);
        this.sponsorBeacons = new SponsorBeaconService(session, sites, beaconBlock(), announcements,
                server, beaconLog(), runtimeStore, sponsorTokens, new Random());
        this.supplyDrops = new SupplyDropService(session, virtualTime, this::supplyDropTimes, sites,
                ArenaSites.landing(this::fillCrate), announcements, server,
                supplyLog(), runtimeStore, new Random(), SUPPLY_LOOT);
        this.monsterWaves = new MonsterWaveService(new Spawns(spawner()), monsterLog(),
                new Random());
        this.simulation = new MannequinSimService(session, mannequins(), simulationLog());

        // ---- the clock, and what happens when it runs out
        this.roundExpiry = new RoundExpiryService(null, this::whoCanDecide, expiryPrompt(),
                message -> roundLog.log("ROUND", message), session::phase);
        this.timer = new GameTimerService(session, virtualTime, border, roundExpiry,
                () -> borderPhases, core.bossBars(), core.scoreboards(), this::everybodyOnline,
                this::gracePeriodEnded, GameTimerService.viaScheduling(plugin));

        // ---- the run-up
        this.startup = new StartupSequenceService(plugin, session, arena, this::onlinePlayers,
                this::fillTheArena, core.effects(), simulation, startupTold(), log);
        this.countdown = new CountdownService(plugin, session, arena, this::onlinePlayers,
                core.bossBars(), core.effects(), core.messages(), countdownTold(), log);
        this.control = new GameControlService(session, countdown::isCountdownActiveFor,
                arena.initStage(), startup.startupStage(), countdown.startStage());
        this.preflight = new PreflightCheckService(session, opTracker, lootTables,
                arena::centreDescription, () -> server.getPluginManager().isPluginEnabled("WorldEdit"),
                uuid -> server.getPlayer(uuid) != null, gamemasters::onlineActive,
                supplyDropPlan(), sponsorShopStatus(), core.effects()::problems);

        // ---- the fourteen custom items. Built here, registered with Core in start() — see that method's
        // note on why a side effect on the server does not belong in a constructor. Forgetting to build one
        // of these is exactly the bug EveryItemIsRegisteredTest exists to catch: the service compiles, its
        // own tests pass, and the only symptom is an empty item page.
        this.arenaItems = new ArenaItemService(core.itemAbilities(), core.items(), session::phase,
                tracking(), settings());
        this.combatItems = new CombatItemService(core.itemAbilities(), core.items(), session::phase,
                smokescreen(), medicine(), storm(), splash(), aura(), settings());
        this.mobilityItems = new MobilityItemService(core.itemAbilities(), core.items(), session::phase,
                grappling(), repulsion(), launching(), settings());
        this.hermesBoots = new HermesBootsService(core.items(), settings());
        // Built before combatItems, because medicine() reaches for it: the medikit's click is answered by
        // starting a wind-up rather than by healing, and the thing that owns that wind-up has to exist first.
        this.medikitCountdown = new MedikitCountdownService(medikitTreatment(),
                task -> {
                    var scheduled = Scheduling.globalTimer(plugin, 20L, 20L, handle -> task.run());
                    return scheduled::cancel;
                });
        this.survivalItems = new SurvivalItemService(core.itemAbilities(), core.items(), session::phase,
                feasting(), armoury(), rescue(), volley(), itemVoice(), System::currentTimeMillis, new Random(),
                settings());
    }

    /**
     * Ties the knot: subscribes everything that wanted the session, registers the listeners, defines the
     * loot tables and starts the clock.
     *
     * <p>Separate from the constructor because every one of these has a side effect on the server — a
     * listener registration, a scheduled task, an entry in a shared registry. A constructor that did them
     * would make this class impossible to build in a test without all of it happening.
     */
    public HungerGamesServices start() {
        // Built once and held, because the hotbar items open the same pages the commands do — two openers
        // would be two menu trees to keep in step.
        this.screens = screens();

        // The fan-out, now that everything it calls exists. Order is the order they run in, and it matters
        // once: the op tracker restores operator status on the phase change that the announcer describes,
        // and an announcement naming somebody as a plain player before their OP came back reads as a lie.
        events.also(roundLog)
                .also(new AnnouncementListener(this::broadcast, this::nameOf, settings()))
                .also(phaseWatcher())
                .also(new WinnerFinishListener(
                        (after, what) -> Scheduling.globalLater(plugin, after.toSeconds() * 20L, what),
                        this::finishTheRound));

        // Before anything can play one. Without this every cue this module names is unknown to Core, which
        // answers by playing nothing — see HungerGamesCues for how that shipped once already.
        int cues = HungerGamesCues.defineAllIn(core.effects());
        // A layered cue's later sounds need somewhere to be scheduled; without this they all fire at once,
        // which is audible but flat.
        core.effects().delayedPlaybackVia((millis, what) ->
                Scheduling.globalLater(plugin, Math.max(1L, millis / 50L), what));
        log.info("{} sound and particle cue(s) defined.", cues);

        // The fourteen custom items and their abilities, into Core's registries — see the constructor's note
        // by the four fields above. Nothing before this point may fire one of them; nothing after this
        // point is missing from the item page or the sponsor shop.
        arenaItems.register();
        combatItems.register();
        mobilityItems.register();
        survivalItems.register();
        hermesBoots.register();
        // Once a second: grants and revokes the flight the boots earn, and spends the budget while it is
        // actually being used — see tickHermesBoots's own note for why this is a tick rather than a click.
        Scheduling.globalTimer(plugin, 20L, 20L, handle -> tickHermesBoots());
        // Counted out loud. Three separate times in this port, finished and tested code was simply never
        // called — the session store, this whole class, and these four services — and every time the only
        // evidence on a clean boot was a log line that was *absent*. A number here is the difference
        // between "it booted" and "it booted with the items in it".
        log.info("{} custom item(s) defined for this module.",
                core.items().all().stream()
                        .filter(item -> "hungergames".equals(item.plugin()))
                        .count());

        startTheHttpApi();

        // So the writer thread does not outlive a reload, and so the last queued lines are flushed.
        context.closeWith(() -> {
            roundLogWriter.shutdown();
            if (!roundLogWriter.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("The round log still had lines queued when the module stopped.");
            }
        });

        defineTheLootTables();

        context.listener(new ConnectionListener(session, spectators, presentation, timer,
                core.messages(), message -> roundLog.log("ROUND", message), settings()));
        context.listener(new LobbyListener(session, lobbyBox(), core.messages(), settings()));
        context.listener(new EliminationListener(session, spectators, this::deathSpectacle, eviction(),
                message -> roundLog.log("ROUND", message), settings()));
        context.listener(new PortalListener(session::phase, () -> arena.centre().orElse(null),
                core.messages(), message -> roundLog.log("ROUND", message), settings()));
        // The Stupidness Protector is passive — see SurvivalItemService's javadoc — so it has no ability of
        // its own and is caught here instead, at the one place that actually knows what killed somebody.
        context.listener(new StupidnessProtectorListener(survivalItems::wouldSaveFrom));
        // Any hit at all stops a medikit landing — the price of the most valuable item in the shop, and the
        // only counterplay to somebody using one mid-fight.
        context.listener(new MedikitInterruptListener(medikitCountdown::interrupt));
        // A thrown bottle of krückauwasser landing. Its own listener because the item is a projectile:
        // where it lands is the item, and a hitscan version of it cannot be dodged.
        context.listener(new KrueckauwasserListener(krueckauImpact()));
        // Team, all, or spectator — see ChatChannelService's class note for the bug this fixes: there was
        // no channel at all before this, so a team's fight plan was overheard by everybody they were
        // fighting, and being eliminated changed nothing about who could still read a tribute's words.
        context.listener(new de.raindancer.modules.hungergames.listener.ChatChannelListener(
                plugin, session, chatChannels, core.messages(), server));

        // The three items a gamemaster runs a tournament from. Without them this module's whole "click, do
        // not type" arrangement has no first click — see AdminHotbarListener.
        this.hotbar = new AdminHotbarListener(plugin,
                new AdminHotbarListener.Pages() {
                    @Override
                    public void admin(Player viewer) {
                        screens.admin(viewer);
                    }

                    @Override
                    public void control(Player viewer) {
                        // The same page, opened at its round-control section. One entry point rather than a
                        // second opener method: the admin suite is where a round is run from, and a separate
                        // door into the middle of it is a second menu tree to keep in step.
                        screens.admin(viewer);
                    }
                },
                who -> countdown.run(who.getUniqueId()),
                session::phase, settings());
        context.listener(hotbar);

        // The protection period, enforced. It was measured, announced and counted down on the boss bar, and
        // nothing ever asked isGraceActive() — so the round told forty people they were safe and let the
        // difficulty it had just set do what it does. See GracePeriodListener.
        context.listener(new de.raindancer.modules.hungergames.listener.GracePeriodListener(
                timer::isGraceActive, session::phase, session::isWhitelisted));

        // The cornucopia, as a Core protected area. Core owns the four listeners that ask it; this module
        // only says where the area is and what may happen inside it.
        core.land().provider(new de.raindancer.modules.hungergames.service.CornucopiaProvider(
                settings(), session::phase, () -> arena.centre().orElse(null)));

        supplyDrops.restoreFromStore();
        opTracker.restoreFromStore();
        sponsorBeacons.start();

        // Every second: the round's clock, and the two timetables that are pure functions of it.
        Scheduling.globalTimer(plugin, TICK_PERIOD, TICK_PERIOD, task -> tick());

        if (session.phase() == GamePhase.RUNNING) {
            // The server came back mid-round. See HungerGamesSettings.offlineTimePolicy for why the
            // elapsed time is not simply wall-clock since the round started.
            Duration elapsed = elapsedAcrossTheRestart();
            timer.resume(elapsed, 0);
            log.info("A round was already running; resumed at {} elapsed.",
                    de.raindancer.core.world.time.Times.describe(elapsed));
        }

        settingsStore.onChange(this::settingsChanged);

        return new HungerGamesServices(plugin, server, log, core.messages(), context.chat(), brand,
                session, control, preflight, deathmatch, chatChannels, settingsStore::current, screens,
                core.items(), core.itemFactory());
    }

    // ==================== the tick ====================

    /**
     * One second of a running round.
     *
     * <p>All four timetables are driven from one task rather than four. They are all functions of the same
     * elapsed time, and four independent tasks means four slightly different ideas of what time it is —
     * which is how the version this replaces managed to announce a supply drop for a moment that had
     * already passed.
     */
    private void tick() {
        if (session.phase() != GamePhase.RUNNING) {
            return;
        }
        Duration elapsed = virtualTime.elapsed();
        timer.tick();
        supplyDrops.tick(elapsed);
        sponsorBeacons.tick(elapsed, this::beaconTimes);
        sponsorTokens.tick(elapsed, tokenSchedule(), server::getPlayer);
        roundExpiry.tick(elapsed);
        // Advances every exmatrikulator aura currently up — see SurvivalItemService.pulse()'s javadoc for
        // why that is an externally-driven method rather than a BukkitRunnable the service would have to
        // cancel correctly on every exit path. Guarded by the same phase check as everything else above:
        // an aura that outlives its round is a lightning storm nobody asked for.
        survivalItems.pulse();
    }

    // ==================== the seams ====================

    private HungerGamesSettings settings() {
        return settingsStore.current();
    }

    /** Somebody's name, or their UUID when nothing knows it — never null, because it goes into wording. */
    private String nameOf(UUID uuid) {
        return session.participants().nameOf(uuid)
                .or(() -> Optional.ofNullable(server.getPlayer(uuid)).map(Player::getName))
                .orElseGet(uuid::toString);
    }

    /*
     * The four round-log sinks.
     *
     * Four methods rather than one generic factory, because the four services each declare their own
     * RoundLog interface — structurally identical, and four distinct types, so no single lambda satisfies
     * all of them. What is shared is written once in coordsOf.
     */

    private SupplyDropService.RoundLog supplyLog() {
        return (category, message, where) -> roundLog.log("SUPPLY", message, coordsOf(where));
    }

    private SponsorBeaconService.RoundLog beaconLog() {
        return (category, message, where) -> roundLog.log("SPONSOR", message, coordsOf(where));
    }

    private MonsterWaveService.RoundLog monsterLog() {
        return (category, message, where) -> roundLog.log("MONSTERS", message, coordsOf(where));
    }

    private MannequinSimService.RoundLog simulationLog() {
        return (category, message, where) -> roundLog.log("SIMULATION", message, coordsOf(where));
    }

    /** A Bukkit location as the plain record the round log takes — null stays null, meaning "no coordinates". */
    private static RoundLogService.Coordinates coordsOf(Location where) {
        if (where == null || where.getWorld() == null) {
            return null;
        }
        return new RoundLogService.Coordinates(where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ());
    }

    /**
     * How often sponsor tokens arrive, and how many.
     *
     * <p>Read from the settings every tick rather than captured once, so a gamemaster who doubles the rate
     * mid-round gets it from the next wave rather than from the next restart.
     */
    private SponsorTokenService.Schedule tokenSchedule() {
        HungerGamesSettings now = settings();
        return new SponsorTokenService.Schedule(
                now.sponsorTokenFirstAfter(), now.sponsorTokenInterval(),
                now.sponsorTokenAmountPerInterval(), now.sponsorTokenMaxPerPlayer(),
                now.sponsorTokenOnlyAlive());
    }

    private List<Player> onlinePlayers() {
        return List.copyOf(server.getOnlinePlayers());
    }

    private Collection<UUID> everybodyOnline() {
        return server.getOnlinePlayers().stream().map(Player::getUniqueId).map(uuid -> uuid).toList();
    }

    /**
     * Operator status, behind a seam so {@code OpTrackerService} can be tested with a map.
     *
     * <h2>Why this asks Bukkit directly, and is the one place in this module that may</h2>
     * Operator status has no owner in Core, and should not have one. Core owns <em>permissions</em> —
     * {@code Grants} hands out nodes, and a node is a thing a plugin can reason about. Being an operator is
     * different in kind: it is the server's own answer to "may this person do absolutely anything", it lives
     * in {@code ops.json}, and wrapping it in a Core API would imply Core could grant it. It cannot, and a
     * façade that looked as though it could is worse than none.
     *
     * <p>{@code PlayerDirectory} is the right answer for <em>choosing</em> a player and is used everywhere
     * this module does that. It is the wrong answer here: it lists people for a menu, and this needs to set a
     * flag on somebody who may never have been in one.
     */
    private OpTrackerService.OpAccess opAccess() {
        return new OpTrackerService.OpAccess() {
            @Override
            public boolean isOp(UUID uuid) {
                return theOperatorFlagOf(uuid).isOp();
            }

            @Override
            public void setOp(UUID uuid, boolean op) {
                theOperatorFlagOf(uuid).setOp(op);
            }
        };
    }

    /**
     * Somebody's {@code ops.json} entry.
     *
     * <p>Named this way, and kept to one line, so that the one direct {@code getOfflinePlayer} call in this
     * module is a single place with an explanation attached rather than two calls in a lambda — see
     * {@link #opAccess()} for why Core has no wrapper for this and should not grow one.
     */
    private org.bukkit.OfflinePlayer theOperatorFlagOf(UUID uuid) {
        return server.getOfflinePlayer(uuid);
    }

    /**
     * Where a tribute's dying is marked.
     *
     * <p>Through Core's {@code Effects} with a cue name rather than a hard-coded sound, so a server that
     * rebinds what death sounds like rebinds it once for everything.
     */
    private void deathSpectacle(Location where, boolean killed) {
        if (where.getWorld() == null) {
            return;
        }
        core.effects().playAt(where.getWorld().getName(), where.getX(), where.getY(), where.getZ(),
                killed ? HungerGamesCues.KILL : HungerGamesCues.ELIMINATION);
    }

    /** Kicking and banning, which is what {@code game.death-action} can ask for. */
    private EliminationListener.Eviction eviction() {
        return new EliminationListener.Eviction() {
            @Override
            public void kick(Player who, String because) {
                who.kick(core.messages().get("hungergames.elimination-kick"));
            }

            @Override
            public boolean ban(Player who, String because) {
                // Core's own punishment record, not Bukkit's ban list: a ban that only exists in
                // banned-players.txt is one no staff screen can see, explain or lift.
                core.punishments().punish(who.getUniqueId(),
                        de.raindancer.core.moderation.punishment.PunishmentKind.BAN,
                        null, because, null);
                who.kick(core.messages().get("hungergames.elimination-ban"));
                return true;
            }
        };
    }

    /** Monsters, through Core's {@code Spawns} — the arithmetic of a wave is Core's, the world is ours. */
    private Spawner spawner() {
        return new Spawner() {
            @Override
            public boolean spawn(Spot spot, String type) {
                World world = server.getWorld(spot.world());
                if (world == null) {
                    return false;
                }
                try {
                    world.spawnEntity(new Location(world, spot.centreX(), spot.y(), spot.centreZ()),
                            org.bukkit.entity.EntityType.valueOf(type.toUpperCase(java.util.Locale.ROOT)));
                    return true;
                } catch (IllegalArgumentException noSuchType) {
                    // A wave configured with a creature this server does not have. Refused rather than
                    // substituted: a gamemaster who asked for ravagers and got zombies would not know.
                    log.warn("There is no creature called '{}' on this server, so none was spawned.", type);
                    return false;
                }
            }

            @Override
            public boolean isLoaded(Spot spot) {
                World world = server.getWorld(spot.world());
                return world != null && world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
            }
        };
    }

    /** Mannequins, for a rehearsal with nobody real in the arena. */
    private MannequinSimService.Mannequins mannequins() {
        return new MannequinSimService.Mannequins() {
            @Override
            public UUID spawn(Location base, String displayName, TeamColour colour) {
                var mannequin = base.getWorld().spawn(base, org.bukkit.entity.Mannequin.class);
                mannequin.customName(net.kyori.adventure.text.Component.text(displayName,
                        colour == null ? net.kyori.adventure.text.format.NamedTextColor.WHITE
                                : net.kyori.adventure.text.format.NamedTextColor.WHITE));
                mannequin.setCustomNameVisible(true);
                return mannequin.getUniqueId();
            }

            @Override
            public void remove(UUID entityId) {
                var entity = server.getEntity(entityId);
                if (entity != null) {
                    entity.remove();
                }
            }

            @Override
            public Optional<Location> locationOf(UUID entityId) {
                var entity = server.getEntity(entityId);
                return entity == null ? Optional.empty() : Optional.of(entity.getLocation());
            }

            @Override
            public void moveTo(UUID entityId, Location where) {
                var entity = server.getEntity(entityId);
                if (entity != null) {
                    entity.teleport(where);
                }
            }

            @Override
            public void markEliminated(Location location, boolean hadKiller) {
                deathSpectacle(location, hadKiller);
            }
        };
    }

    /** The three-by-three base and the beacon on it. */
    private SponsorBeaconService.BeaconBlock beaconBlock() {
        return new SponsorBeaconService.BeaconBlock() {
            @Override
            public void place(Location site, HungerGamesSettings settings) {
                World world = site.getWorld();
                if (world == null) {
                    return;
                }
                int y = site.getBlockY() - 1;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(site.getBlockX() + dx, y, site.getBlockZ() + dz)
                                .setType(settings.supplyDropBaseMaterial(), false);
                    }
                }
                world.getBlockAt(site.getBlockX(), site.getBlockY(), site.getBlockZ())
                        .setType(Material.BEACON, false);
            }

            @Override
            public void remove(Location site) {
                World world = site.getWorld();
                if (world != null) {
                    world.getBlockAt(site.getBlockX(), site.getBlockY(), site.getBlockZ())
                            .setType(Material.AIR, false);
                }
            }
        };
    }

    /**
     * A team's helmet, in that team's colour — how a tribute tells friend from foe across an arena.
     *
     * <p>Through Core's {@code Icons} rather than a bare {@code ItemStack}, so the name is MiniMessage and
     * the stack is built the same way every other named item on this server is. {@code team.display()}
     * already carries the emblem's glyph, so the helmet reads as the team rather than as its colour alone.
     */
    private ItemStack teamHelmet(de.raindancer.core.social.team.Team team) {
        return de.raindancer.core.ui.menu.Icons.of(team.badge(),
                "<" + team.colour().name().toLowerCase(java.util.Locale.ROOT) + ">" + team.display(),
                List.of("<gray>Your team."));
    }

    /** The sponsor token item, defined once with Core so its NBT key and lore are the server's. */
    private de.raindancer.core.content.items.CustomItem tokenItem() {
        HungerGamesSettings now = settings();
        de.raindancer.core.content.items.CustomItem token =
                de.raindancer.core.content.items.CustomItem.builder("hungergames", "sponsor-token")
                        .material(now.sponsorTokenMaterial())
                        .name(now.sponsorTokenName())
                        .lore(now.sponsorTokenLore())
                        .build();
        // defineIfAbsent, so a server that has re-skinned the token keeps its own version.
        core.items().defineIfAbsent(token);
        return core.items().byKey(token.key()).orElse(token);
    }

    // ==================== the fourteen custom items ====================

    /**
     * Every enemy a combat or survival item may catch nearby: living tributes who are still alive and not
     * spectating — never the holder, never a mannequin standing in for one — and, the source plugin's own
     * default for every item that asked, hostile mobs as well.
     *
     * <p>The source's {@code enemiesAround} in one place rather than five: the smoke bomb, repulse, the
     * aura of protection, the exmatrikulator's volley and the Stupidness Protector's shove all asked this
     * exact question, each with its own copy of the same loop. Distance and world are both the caller's
     * radius against {@code holder.getWorld()}, so an item used across worlds catches nobody rather than
     * comparing coordinates that do not mean the same thing.
     */
    private List<LivingEntity> enemiesNear(Player holder, double radius) {
        List<LivingEntity> found = new ArrayList<>();
        for (Entity entity : holder.getWorld().getNearbyEntities(holder.getLocation(), radius, radius, radius)) {
            if (entity.equals(holder) || entity instanceof org.bukkit.entity.Mannequin) {
                continue;
            }
            if (entity instanceof Player other) {
                if (other.getGameMode() != GameMode.SPECTATOR && session.participants().isAlive(other.getUniqueId())) {
                    found.add(other);
                }
            } else if (entity instanceof Monster monster) {
                found.add(monster);
            }
        }
        return found;
    }

    /** A {@link Duration} as whole ticks, rounded down — never zero for a positive duration, so a caller
     *  that checked {@code !duration.isZero()} first never schedules or applies "nothing at all". */
    private static long ticksOf(Duration duration) {
        return Math.max(1L, duration.toMillis() / 50L);
    }

    /**
     * Fully hides a player: the standard invisibility potion effect, plus their armour lifted out of its
     * slots and handed back when the duration runs out.
     *
     * <p>Invisibility alone does not hide worn armour — the source plugin's own {@code InvisibilityCloak}
     * exists for exactly that reason, and both the Invisibility Cloak and the smoke bomb's own vanishing
     * act use it. The armour is only lifted once per holder: a smoke bomb thrown by somebody already
     * cloaked must not store an already-empty set of slots over the real one, which is how the source lost
     * armour the first time this was written.
     *
     * <p>The restore is scheduled on the holder's own entity, and the map entry is dropped the moment it
     * runs whether or not the holder is still online — never left behind for a player who quit mid-cloak,
     * which is what would otherwise grow this map by one entry per player who ever left during one.
     */
    private void hideFully(Player holder, Duration duration) {
        long ticks = ticksOf(duration);
        holder.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) ticks, 0, false, false, true));
        UUID id = holder.getUniqueId();
        if (!hiddenArmour.containsKey(id)) {
            PlayerInventory inventory = holder.getInventory();
            ItemStack[] armour = inventory.getArmorContents();
            boolean hasArmour = false;
            for (ItemStack piece : armour) {
                if (piece != null && !piece.getType().isAir()) {
                    hasArmour = true;
                    break;
                }
            }
            if (hasArmour) {
                hiddenArmour.put(id, armour);
                inventory.setArmorContents(new ItemStack[4]);
            }
        }
        Scheduling.entityLater(plugin, holder, ticks, () -> restoreHiddenArmour(id));
    }

    /** The other half of {@link #hideFully} — see there for why the map entry always goes, online or not. */
    private void restoreHiddenArmour(UUID id) {
        ItemStack[] armour = hiddenArmour.remove(id);
        if (armour == null) {
            return;
        }
        Player player = server.getPlayer(id);
        if (player == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] current = inventory.getArmorContents();
        for (int i = 0; i < 4 && i < armour.length; i++) {
            if (armour[i] == null || armour[i].getType().isAir()) {
                continue;
            }
            if (current[i] == null || current[i].getType().isAir()) {
                current[i] = armour[i];
            } else {
                // The slot is occupied again — the holder put something else on while hidden. Handed to
                // them rather than overwritten, the same courtesy the source plugin's own restore gave.
                inventory.addItem(armour[i]).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
        }
        inventory.setArmorContents(current);
    }

    /** A single lightning bolt: a visual strike, then area damage, fire and an optional knock-up. */
    private void strikeLightningBolt(World world, Location strike, Player caster, int damageRadius,
                                     double bonusDamage, Duration fireDuration, boolean knockUp) {
        // Effect only — never Bukkit's own lightning damage. The source's strikes are cosmetic on purpose,
        // so the numbers below are the entire damage a bolt does, not a bonus on top of one.
        world.strikeLightningEffect(strike);
        for (Entity entity : world.getNearbyEntities(strike, damageRadius, damageRadius, damageRadius)) {
            if (!(entity instanceof LivingEntity living) || living.getUniqueId().equals(caster.getUniqueId())) {
                continue;   // the caster is always spared, so nobody kills themselves with their own storm
            }
            if (bonusDamage > 0) {
                living.damage(bonusDamage, caster);
            }
            if (!fireDuration.isZero()) {
                living.setFireTicks((int) ticksOf(fireDuration));
            }
            if (knockUp) {
                living.setVelocity(living.getVelocity().add(new Vector(0, 0.6, 0)));
            }
        }
    }

    /** A single bolt against one already-chosen target — the exmatrikulator's volley, which picks its
     *  victims itself rather than scanning a radius around the strike the way a lightning storm does. */
    private void strikeChosenTarget(LivingEntity victim, Player caster, double damage, Duration fireDuration) {
        victim.getWorld().strikeLightningEffect(victim.getLocation());
        if (damage > 0) {
            victim.damage(damage, caster);
        }
        if (!fireDuration.isZero()) {
            victim.setFireTicks((int) ticksOf(fireDuration));
        }
    }

    // -------------------- ArenaItemService --------------------

    /**
     * Pointing at the nearest living tribute. Search radius and glow duration are read from the settings at
     * the moment of use rather than captured once, the same reasoning as every other live-tuned number in
     * this file: a gamemaster who widens the search mid-round gets it from the next reading.
     */
    private ArenaItemService.Tracking tracking() {
        return use -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            HungerGamesSettings now = settings();
            double radius = now.fiendfinderSearchRadius();
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (UUID uuid : session.participants().alive()) {
                if (uuid.equals(holder.getUniqueId())) {
                    continue;
                }
                Player candidate = server.getPlayer(uuid);
                if (candidate == null || candidate.getGameMode() == GameMode.SPECTATOR
                        || !candidate.getWorld().equals(holder.getWorld())) {
                    continue;
                }
                double distance = candidate.getLocation().distance(holder.getLocation());
                if (radius > 0 && distance > radius) {
                    continue;   // zero means "no limit" — see the setting's own description
                }
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = candidate;
                }
            }
            if (nearest == null) {
                holder.sendMessage(core.messages().get("hungergames.item-fiendfinder-nobody"));
                return false;
            }
            Duration glowDuration = Duration.ofSeconds(now.fiendfinderGlowDuration());
            nearest.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                    (int) ticksOf(glowDuration), 0, false, false, true));
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_FIENDFINDER);
            holder.sendMessage(core.messages().get("hungergames.item-fiendfinder"));
            holder.sendMessage(core.messages().get("hungergames.item-fiendfinder-found",
                    "who", nearest.getName(),
                    "distance", String.valueOf((int) Math.round(nearestDistance))));
            // The person found is told. Being tracked without knowing it is the version of this item that
            // has no counterplay at all, and the source told them for exactly that reason.
            nearest.sendMessage(core.messages().get("hungergames.item-fiendfinder-revealed"));
            trackOnTheActionBar(holder.getUniqueId(), nearest.getUniqueId(), glowDuration);
            return true;
        };
    }

    /**
     * A rotating arrow and a live distance, on the action bar, for as long as the target keeps glowing.
     *
     * <h2>Why an arrow drawn as text rather than the vanilla locator bar</h2>
     * Minecraft's own locator bar — the off-screen marker other games call a "waypoint arrow" — has no
     * plugin API on this server's Paper build at all: nothing under {@code org.bukkit} or Paper's own
     * extensions creates or targets one. Building it anyway would mean reaching past both APIs into the
     * raw protocol, version-locked to exactly this Minecraft release — the kind of dependency this project
     * takes on WorldEdit for and writes itself for nothing else. An arrow built from the holder's own yaw
     * and the bearing to the target is available today, on every Paper version this module supports, and
     * needs nothing from the server that was not already being read for the glow effect.
     *
     * <h2>Why it only runs for the glow's own duration</h2>
     * The source's Fiendfinder was one line of text, read once, about where the target was standing at the
     * moment of the click — a snapshot, not a tracker. A live-updating arrow that ran forever would turn a
     * single reading into a permanent radar, which is a different balance decision from the one
     * {@code items.fiendfinder.glow-duration} already makes about how long a reveal lasts. Tying the two
     * together means a server tuning how long somebody stays lit is also tuning how long the holder can
     * navigate towards them, without a second setting to keep in step with the first.
     *
     * <h2>Why it is private to the holder</h2>
     * {@link ActionBarPriority#HIGH}, shown only to the one player, on its own owner slot — the same reason
     * the Hermes' boots countdown and the medikit's wind-up are private: this is the holder's own read of
     * the Fiendfinder they spent, not something the person revealed, or anybody else nearby, should see.
     */
    private void trackOnTheActionBar(UUID holderId, UUID targetId, Duration forHowLong) {
        Player holder = server.getPlayer(holderId);
        if (holder == null) {
            return;
        }
        long totalTicks = ticksOf(forHowLong);
        long[] elapsed = {0L};
        Scheduling.entityTimer(plugin, holder, 20L, 20L, scheduled -> {
            elapsed[0] += 20L;
            Player current = server.getPlayer(holderId);
            Player target = server.getPlayer(targetId);
            if (current == null || target == null || !session.participants().isAlive(targetId)
                    || elapsed[0] >= totalTicks) {
                if (current != null) {
                    core.actionBars().clear(holderId, FIENDFINDER_BAR);
                }
                scheduled.cancel();
                return;
            }
            Vector toTarget = target.getLocation().toVector().subtract(current.getLocation().toVector());
            double distance = toTarget.length();
            String arrow = compassArrow(current.getLocation().getYaw(), toTarget);
            core.actionBars().show(holderId, FIENDFINDER_BAR,
                    core.messages().get("hungergames.item-fiendfinder-tracking",
                            "arrow", arrow, "distance", String.valueOf((int) Math.round(distance))),
                    Duration.ofSeconds(2), ActionBarPriority.HIGH);
        });
    }

    /**
     * One of eight arrows for the direction {@code toTarget} lies in, relative to {@code facingYaw}.
     *
     * <p>Minecraft's yaw is measured clockwise from south (0°) rather than the usual east-from-positive-x
     * convention, which is why the bearing below reads {@code -dx, dz} rather than {@code dz, dx}: it is
     * the same formula {@code Location.setDirection} uses in reverse, so a bearing computed here and a yaw
     * read from the engine always agree about which way is which.
     */
    private static String compassArrow(float facingYaw, Vector toTarget) {
        double bearing = Math.toDegrees(Math.atan2(-toTarget.getX(), toTarget.getZ()));
        double relative = ((bearing - facingYaw) % 360 + 360) % 360;   // normalised to [0, 360)
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = (int) Math.round(relative / 45.0) % 8;
        return arrows[index];
    }

    // -------------------- CombatItemService --------------------

    /** Fogging enemies nearby, and hiding the thrower — see {@link #hideFully}. */
    private CombatItemService.Smokescreen smokescreen() {
        return (use, radius, enemyEffectDuration, invisibilityDuration) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            Location centre = holder.getLocation();
            int fogged = 0;
            for (Player other : enemiesNear(holder, radius).stream()
                    .filter(entity -> entity instanceof Player).map(entity -> (Player) entity).toList()) {
                other.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                        (int) ticksOf(enemyEffectDuration), 0));
                other.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        (int) ticksOf(enemyEffectDuration), 1));
                fogged++;
            }
            core.effects().playAt(centre.getWorld().getName(), centre.getX(), centre.getY() + 1, centre.getZ(),
                    HungerGamesCues.ITEM_SMOKE_BOMB);
            if (!invisibilityDuration.isZero()) {
                hideFully(holder, invisibilityDuration);
                holder.sendMessage(core.messages().get("hungergames.item-smoke-bomb",
                        "count", String.valueOf(fogged),
                        "seconds", String.valueOf(invisibilityDuration.toSeconds())));
            } else {
                holder.sendMessage(core.messages().get("hungergames.item-smoke-bomb-no-cloak",
                        "count", String.valueOf(fogged)));
            }
            return true;
        };
    }

    /**
     * The medikit's click.
     *
     * <p>Two answers, and which one is given is the server's own tuning. With
     * {@code items.medikit.countdown-seconds} at zero it heals on the spot and returns true, so Core takes
     * the item. With anything above zero it starts a wind-up and returns <b>false</b> — the item is not spent
     * yet, exactly as the source had it, so a treatment cancelled by a hit costs the holder nothing and the
     * medikit is still there to try again with. {@link MedikitCountdownService} takes it when the heal
     * actually lands.
     */
    private CombatItemService.Medicine medicine() {
        return (use, windUp, regenerationDuration, regenerationAmplifier, absorptionDuration,
                absorptionAmplifier) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            if (!windUp.isZero() && medikitCountdown.begin(holder.getUniqueId())) {
                return false;
            }
            healWithAMedikit(holder);
            return true;
        };
    }

    /**
     * What a medikit actually does, once it lands.
     *
     * <p>Its own method because it is reached from two places now — the instant version above and the end of
     * a wind-up — and the version where those two drifted apart is one where a server that switched the
     * countdown off got a quietly different heal.
     */
    private void healWithAMedikit(Player holder) {
        HungerGamesSettings now = settings();
        var maxHealth = holder.getAttribute(Attribute.MAX_HEALTH);
        holder.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        holder.setFoodLevel(20);
        holder.setSaturation(20f);
        holder.setFireTicks(0);
        Duration regeneration = Duration.ofSeconds(now.medikitRegenSeconds());
        if (!regeneration.isZero()) {
            holder.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                    (int) ticksOf(regeneration), Math.max(0, now.medikitRegenLevel() - 1)));
        }
        Duration absorption = Duration.ofSeconds(now.medikitAbsorptionSeconds());
        if (!absorption.isZero()) {
            holder.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                    (int) ticksOf(absorption), Math.max(0, now.medikitAbsorptionLevel() - 1)));
        }
        core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_MEDIKIT);
        holder.sendMessage(core.messages().get("hungergames.medikit-healed"));
    }

    /**
     * Everything the medikit's wind-up says and does in the world.
     *
     * <p>The count goes on the action bar and the two one-off lines go to chat, which is the source's own
     * split and the right one: a number that changes every second must not be four lines of chat, and
     * "you were interrupted" must survive being replaced a tick later.
     */
    private MedikitCountdownService.Treatment medikitTreatment() {
        return new MedikitCountdownService.Treatment() {

            @Override
            public boolean stillThere(UUID holder) {
                Player player = server.getPlayer(holder);
                return player != null && player.isOnline() && !player.isDead();
            }

            @Override
            public void applied(UUID holder, int seconds) {
                Player player = server.getPlayer(holder);
                if (player != null) {
                    player.sendMessage(core.messages().get("hungergames.medikit-applied",
                            "seconds", String.valueOf(seconds)));
                }
            }

            @Override
            public void counting(UUID holder, int secondsLeft) {
                core.actionBars().show(holder, MEDIKIT_BAR,
                        core.messages().get("hungergames.medikit-counting",
                                "seconds", String.valueOf(secondsLeft)),
                        Duration.ofSeconds(2), ActionBarPriority.HIGH);
            }

            @Override
            public void alreadyRunning(UUID holder) {
                Player player = server.getPlayer(holder);
                if (player != null) {
                    player.sendMessage(core.messages().get("hungergames.medikit-already"));
                }
            }

            @Override
            public void interrupted(UUID holder) {
                core.actionBars().clear(holder, MEDIKIT_BAR);
                Player player = server.getPlayer(holder);
                if (player != null) {
                    player.sendMessage(core.messages().get("hungergames.medikit-interrupted"));
                }
            }

            @Override
            public boolean spendAndHeal(UUID holder) {
                Player player = server.getPlayer(holder);
                if (player == null) {
                    return false;
                }
                core.actionBars().clear(holder, MEDIKIT_BAR);
                // Taken only now, and only if it is still there. Somebody who dropped it, gave it away or
                // put it in a chest during the wind-up does not get healed by an item they no longer have.
                if (!core.itemFactory().takeOne(player.getInventory(),
                        CombatItemService.PLUGIN + ":" + CombatItemService.MEDIKIT)) {
                    return false;
                }
                healWithAMedikit(player);
                return true;
            }
        };
    }

    /**
     * Calling down a staggered volley of lightning on whatever the holder is looking at.
     *
     * <p>Range and spread are read from the settings, because {@code CombatItemService.Storm} does not
     * carry them — only what a bolt does once it lands is that service's business, not where it lands.
     * The first bolt strikes immediately; the rest are timed by hopping to the global region for the wait
     * and back to the strike's own region to actually touch the world, which is the same two-step every
     * other delayed world-touch in this file uses and is what keeps a storm called from one region from
     * quietly mutating another one on the wrong thread.
     */
    private CombatItemService.Storm storm() {
        return (use, bolts, boltDelay, damageRadius, bonusDamage, fireDuration, knockUp) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            HungerGamesSettings now = settings();
            int range = Math.max(1, now.lightningRange());
            int spread = Math.max(0, now.lightningSpread());
            Block target = holder.getTargetBlockExact(range);
            Location centre = target != null ? target.getLocation().add(0.5, 1, 0.5)
                    : holder.getEyeLocation().add(holder.getEyeLocation().getDirection().multiply(30));
            World world = centre.getWorld();
            if (world == null) {
                return false;
            }
            Random random = new Random();
            for (int i = 0; i < bolts; i++) {
                double ox = spread > 0 ? (random.nextDouble() * 2 - 1) * spread : 0;
                double oz = spread > 0 ? (random.nextDouble() * 2 - 1) * spread : 0;
                Location strike = centre.clone().add(ox, 0, oz);
                Runnable land = () -> Scheduling.region(plugin, strike, () ->
                        strikeLightningBolt(world, strike, holder, damageRadius, bonusDamage, fireDuration, knockUp));
                long delayTicks = boltDelay.toMillis() * i / 50L;
                if (delayTicks <= 0) {
                    land.run();
                } else {
                    Scheduling.globalLater(plugin, delayTicks, land);
                }
            }
            core.effects().playAt(world.getName(), centre.getX(), centre.getY(), centre.getZ(),
                    HungerGamesCues.ITEM_LIGHTNING);
            holder.sendMessage(core.messages().get("hungergames.item-lightning",
                    "bolts", String.valueOf(bolts)));
            return true;
        };
    }

    /**
     * Throwing a bottle of krückauwasser — a real projectile, as the source threw it.
     *
     * <p>The port had this hitscan to the holder's target block, which is a different item: a thrown bottle
     * arcs, can be dodged, can be blocked by the wall you are hiding behind, and lands at your own feet if
     * you aim down. A hitscan splash cannot be avoided by moving, and "get out of the way" is the whole
     * counterplay this item is balanced around.
     *
     * <p>A snowball wearing a splash potion, exactly as the source did it, marked in its persistent data so
     * {@link KrueckauwasserListener} can tell it from a snowball somebody threw. What it does on landing is
     * carried on the projectile too, so a bottle in flight when a gamemaster retunes the item still does
     * what it promised when it was thrown.
     *
     * <p>Always true when there was somebody to throw it: the source consumed the bottle on the throw, not
     * on a hit. A thrown bottle is gone whether or not it caught anybody, which is what makes throwing it
     * a decision.
     */
    private CombatItemService.Splash splash() {
        return (use, radius, nauseaDuration, blindnessDuration) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            Snowball bottle = holder.launchProjectile(Snowball.class);
            bottle.setItem(ItemStack.of(Material.SPLASH_POTION));
            var pdc = bottle.getPersistentDataContainer();
            pdc.set(krueckauMarker(), PersistentDataType.BOOLEAN, true);
            pdc.set(krueckauRadius(), PersistentDataType.DOUBLE, radius);
            pdc.set(krueckauNausea(), PersistentDataType.INTEGER, (int) ticksOf(nauseaDuration));
            pdc.set(krueckauBlindness(), PersistentDataType.INTEGER, (int) ticksOf(blindnessDuration));
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_KRUECKAU_THROW);
            holder.sendMessage(core.messages().get("hungergames.item-krueckau"));
            return true;
        };
    }

    private NamespacedKey krueckauMarker() {
        return new NamespacedKey(plugin, "krueckauwasser");
    }

    private NamespacedKey krueckauRadius() {
        return new NamespacedKey(plugin, "krueckauwasser-radius");
    }

    private NamespacedKey krueckauNausea() {
        return new NamespacedKey(plugin, "krueckauwasser-nausea-ticks");
    }

    private NamespacedKey krueckauBlindness() {
        return new NamespacedKey(plugin, "krueckauwasser-blindness-ticks");
    }

    /**
     * A bottle landing: nausea and blindness for every tribute inside its radius.
     *
     * <p>Everybody in range, the thrower included — the source did not exempt them either, and a splash you
     * can stand in the middle of is what makes throwing one at your own feet a bad idea rather than a free
     * area denial.
     */
    private KrueckauwasserListener.Impact krueckauImpact() {
        return (projectile, worldName, x, y, z) -> {
            var pdc = projectile.getPersistentDataContainer();
            if (!Boolean.TRUE.equals(pdc.get(krueckauMarker(), PersistentDataType.BOOLEAN))) {
                return false;
            }
            World world = server.getWorld(worldName);
            if (world == null) {
                return true;   // ours, so it is removed, even though there is nothing left to splash
            }
            double radius = Optional.ofNullable(pdc.get(krueckauRadius(), PersistentDataType.DOUBLE))
                    .orElse(CombatItemService.KRUECKAUWASSER_RADIUS);
            int nauseaTicks = Optional.ofNullable(pdc.get(krueckauNausea(), PersistentDataType.INTEGER))
                    .orElse((int) ticksOf(CombatItemService.KRUECKAUWASSER_NAUSEA_DURATION));
            int blindTicks = Optional.ofNullable(pdc.get(krueckauBlindness(), PersistentDataType.INTEGER))
                    .orElse((int) ticksOf(CombatItemService.KRUECKAUWASSER_BLINDNESS_DURATION));

            Location impact = new Location(world, x, y, z);
            int caught = 0;
            for (UUID uuid : session.participants().alive()) {
                Player player = server.getPlayer(uuid);
                if (player == null || player.getGameMode() == GameMode.SPECTATOR
                        || !player.getWorld().equals(world)
                        || player.getLocation().distance(impact) > radius) {
                    continue;
                }
                if (nauseaTicks > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nauseaTicks, 0));
                }
                if (blindTicks > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindTicks, 0));
                }
                caught++;
            }
            if (caught > 0) {
                core.effects().playAt(worldName, x, y, z, HungerGamesCues.ITEM_KRUECKAU_IMPACT);
            }
            return true;
        };
    }

    private CombatItemService.Aura aura() {
        return (use, duration, radius, damage, pulseInterval, knockback) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_AURA);
            holder.sendMessage(core.messages().get("hungergames.item-aura",
                    "seconds", String.valueOf(duration.toSeconds())));
            long periodTicks = ticksOf(pulseInterval);
            long totalTicks = Math.max(periodTicks, ticksOf(duration));
            UUID id = holder.getUniqueId();
            long[] elapsed = {0L};
            Scheduling.entityTimer(plugin, holder, periodTicks, periodTicks, scheduled -> {
                elapsed[0] += periodTicks;
                Player current = server.getPlayer(id);
                if (current == null || elapsed[0] > totalTicks) {
                    scheduled.cancel();
                    return;
                }
                Location centre = current.getLocation();
                for (LivingEntity victim : enemiesNear(current, radius)) {
                    if (damage > 0) {
                        victim.damage(damage, current);
                    }
                    if (knockback > 0) {
                        Vector push = victim.getLocation().toVector().subtract(centre.toVector());
                        if (push.lengthSquared() < 1.0E-4) {
                            push = new Vector(1, 0, 0);   // standing exactly where the holder is — pick a side
                        }
                        victim.setVelocity(push.setY(0.4).normalize().multiply(knockback));
                    }
                }
            });
            return true;
        };
    }

    // -------------------- MobilityItemService --------------------

    // -------------------- HermesBootsService --------------------

    /**
     * One second: grants or revokes the flight the boots earn, and spends the budget while it is actually
     * being used.
     *
     * <h2>Why this is a tick rather than a click</h2>
     * There is no click. Hermes' boots are worn, and what they do is entirely a function of whether they
     * are on somebody's feet and whether that somebody is, this second, actually flying — both of which can
     * change without any event this module owns firing (taking a boot off is an inventory click Core does
     * not route through here, and starting or stopping flight is the client's own decision once
     * {@code allowFlight} is true). Asking the question once a second, for everybody alive, is simpler and
     * more robust than trying to catch every path that could change either answer.
     *
     * <h2>Why only flying spends the budget</h2>
     * Real feedback from testing: wearing the boots costs nothing by itself, and neither does walking
     * around in them — only {@link Player#isFlying()} being true drains the second. A tribute who forgets
     * they are wearing them loses nothing; a tribute using them to cross a ravine spends exactly the
     * seconds the crossing took.
     *
     * <h2>Why {@code setAllowFlight} is granted and revoked here rather than left standing</h2>
     * Creative and Spectator already fly on their own terms, and this must never touch that — both the
     * grant and the revoke are skipped for those two modes. For everybody else, flight tracks the boots and
     * the budget exactly: worn and funded grants it, taken off or spent revokes it, every second, so a
     * tribute who runs out mid-air is landed the same tick the budget hits zero rather than a tick later.
     */
    private void tickHermesBoots() {
        if (session.phase() != GamePhase.RUNNING) {
            return;
        }
        HungerGamesSettings now = settings();
        for (UUID uuid : session.participants().alive()) {
            Player tribute = server.getPlayer(uuid);
            if (tribute == null || tribute.getGameMode() == GameMode.CREATIVE
                    || tribute.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            boolean wearing = isWearingHermesBoots(tribute);
            if (wearing) {
                hermesBoots.grantIfAbsent(uuid);
            }
            boolean funded = wearing && hermesBoots.hasFlightLeft(uuid);

            if (tribute.isFlying()) {
                int remaining = hermesBoots.depleteOneSecond(uuid);
                if (remaining <= 0) {
                    tribute.setFlying(false);
                    tribute.setAllowFlight(false);
                    tribute.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                            (int) ticksOf(MobilityItemService.LEAP_SOFT_LANDING), 0, false, false, false));
                    tribute.sendMessage(core.messages().get("hungergames.item-hermes-spent"));
                    core.actionBars().clear(uuid, HERMES_BAR);
                    continue;
                }
                if (remaining <= now.hermesWarningSeconds()) {
                    core.effects().play(uuid, HungerGamesCues.ITEM_HERMES_WARNING);
                    core.actionBars().show(uuid, HERMES_BAR,
                            core.messages().get("hungergames.item-hermes-left",
                                    "seconds", String.valueOf(remaining)),
                            Duration.ofSeconds(2), ActionBarPriority.HIGH);
                }
            }

            if (tribute.getAllowFlight() != funded) {
                tribute.setAllowFlight(funded);
                if (!funded) {
                    tribute.setFlying(false);
                }
            }
        }
    }

    /** Whether this tribute currently has Hermes' boots on their feet, by the item's own key — never a name. */
    private boolean isWearingHermesBoots(Player tribute) {
        ItemStack boots = tribute.getInventory().getBoots();
        return boots != null
                && core.itemFactory().is(boots, HermesBootsService.PLUGIN + ":" + HermesBootsService.HERMES_BOOTS);
    }

    /**
     * Pulling the holder towards a block they are looking at — a continuous flight in a straight line, not
     * a single shove.
     *
     * <h2>Why a real block is required</h2>
     * A grapple aimed at open sky has nothing to hook onto, and declines rather than picking an arbitrary
     * point in the air to fly towards — see the interface note. This is a deliberate change from the port's
     * earlier version, which fell back to a point at maximum range when nothing was hit: that made the item
     * a worse Leap (a single velocity impulse in whatever direction the holder faced) rather than a
     * grappling hook.
     *
     * <h2>Why this recomputes the direction every tick rather than setting one velocity</h2>
     * A single impulse hands the rest of the flight to gravity, which curves it into a parabola — indistinguishable
     * from Leap once it is in the air. Recomputing the vector from the holder's current position to the
     * fixed destination, every tick, is what keeps the flight taut and straight for as long as it lasts:
     * gravity is still pulling on the holder between ticks, and each tick's velocity set corrects for it.
     *
     * <h2>Why it stops on its own</h2>
     * Arrival — within {@link MobilityItemService#GRAPPLING_ARRIVAL_DISTANCE} of the block — lets go so the
     * holder lands rather than hovering at the anchor point forever. {@code maxDuration} is the safety
     * bound for a target the pull cannot actually reach (behind terrain the holder cannot pass, or a target
     * that walked away): without it, aiming somewhere unreachable would pull forever rather than for a
     * bounded few seconds.
     */
    private MobilityItemService.Grappling grappling() {
        return (use, range, speed, maxDuration) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            Block target = holder.getTargetBlockExact((int) range);
            if (target == null) {
                return false;   // nothing solid within range to hook onto — see the interface note
            }
            Location destination = target.getLocation().add(0.5, 1, 0.5);
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_GRAPPLING);
            holder.sendMessage(core.messages().get("hungergames.item-grappling"));

            UUID id = holder.getUniqueId();
            long maxTicks = ticksOf(maxDuration);
            long[] elapsed = {0L};
            Scheduling.entityTimer(plugin, holder, 1L, 1L, scheduled -> {
                elapsed[0]++;
                Player current = server.getPlayer(id);
                if (current == null) {
                    scheduled.cancel();
                    return;
                }
                Vector toTarget = destination.toVector().subtract(current.getLocation().toVector());
                if (toTarget.length() <= MobilityItemService.GRAPPLING_ARRIVAL_DISTANCE
                        || elapsed[0] >= maxTicks) {
                    scheduled.cancel();
                    return;
                }
                current.setVelocity(toTarget.normalize().multiply(speed));
                current.setFallDistance(0f);
            });
            return true;
        };
    }

    /** Shoving everybody within a radius of the holder away, and slowing them briefly. */
    private MobilityItemService.Repulsion repulsion() {
        return (use, radius, velocity, slowFor) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            Location centre = holder.getLocation();
            int shoved = 0;
            for (LivingEntity victim : enemiesNear(holder, radius)) {
                Vector push = victim.getLocation().toVector().subtract(centre.toVector());
                if (push.lengthSquared() < 1.0E-4) {
                    push = new Vector(1, 0, 0);
                }
                victim.setVelocity(push.setY(0.5).normalize().multiply(velocity));
                if (!slowFor.isZero()) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) ticksOf(slowFor), 1));
                }
                shoved++;
            }
            core.effects().playAt(centre.getWorld().getName(), centre.getX(), centre.getY() + 1, centre.getZ(),
                    HungerGamesCues.ITEM_REPULSE);
            holder.sendMessage(core.messages().get("hungergames.item-repulse",
                    "count", String.valueOf(shoved)));
            return true;
        };
    }

    /** Launching the holder forwards, and softening whatever landing follows. */
    private MobilityItemService.Launching launching() {
        return (use, power, softLanding) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            Vector direction = holder.getEyeLocation().getDirection().normalize();
            holder.setVelocity(direction.multiply(power).setY(Math.max(0.5, direction.getY() + 0.5)));
            holder.setFallDistance(0f);
            if (!softLanding.isZero()) {
                holder.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                        (int) ticksOf(softLanding), 0, false, false, false));
            }
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_LEAP);
            holder.sendMessage(core.messages().get("hungergames.item-leap"));
            return true;
        };
    }

    // -------------------- SurvivalItemService --------------------

    /** Feeding somebody a feast: a full food bar, a short regeneration, and golden apples. */
    private SurvivalItemService.Feasting feasting() {
        return (use, regeneration, regenerationLevel, goldenApples) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            holder.setFoodLevel(20);
            holder.setSaturation(20f);
            if (!regeneration.isZero()) {
                holder.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                        (int) ticksOf(regeneration), Math.max(0, regenerationLevel - 1)));
            }
            if (goldenApples > 0) {
                // A plain material reward rather than a custom item, so it goes through Icons the same way
                // the sponsor shop's own material rewards do — see plainStack() above.
                ItemStack apples = de.raindancer.core.ui.menu.Icons.of(Material.GOLDEN_APPLE, null, List.of());
                apples.setAmount(goldenApples);
                holder.getInventory().addItem(apples).values()
                        .forEach(rest -> holder.getWorld().dropItemNaturally(holder.getLocation(), rest));
            }
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_FEAST);
            holder.sendMessage(core.messages().get("hungergames.item-feast"));
            return true;
        };
    }

    /** Putting a full set of armour on the holder, one piece at a time. */
    private SurvivalItemService.Armoury armoury() {
        return (use, pieces) -> {
            Player holder = server.getPlayer(use.player());
            if (holder == null) {
                return false;
            }
            PlayerInventory inventory = holder.getInventory();
            for (Material piece : pieces) {
                ItemStack current = armourSlot(inventory, piece);
                ItemStack fresh = de.raindancer.core.ui.menu.Icons.of(piece, null, List.of());
                if (current == null || current.getType().isAir()) {
                    setArmourSlot(inventory, piece, fresh);
                } else {
                    // The slot is already occupied — handed over rather than replacing whatever they chose
                    // to wear instead, the same courtesy equipOrGive gave in the source.
                    inventory.addItem(fresh).values()
                            .forEach(rest -> holder.getWorld().dropItemNaturally(holder.getLocation(), rest));
                }
            }
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_WAR_KIT);
            holder.sendMessage(core.messages().get("hungergames.item-war-kit"));
            return true;
        };
    }

    private static ItemStack armourSlot(PlayerInventory inventory, Material piece) {
        String name = piece.name();
        if (name.endsWith("_HELMET")) {
            return inventory.getHelmet();
        }
        if (name.endsWith("_CHESTPLATE")) {
            return inventory.getChestplate();
        }
        if (name.endsWith("_LEGGINGS")) {
            return inventory.getLeggings();
        }
        return inventory.getBoots();
    }

    private static void setArmourSlot(PlayerInventory inventory, Material piece, ItemStack stack) {
        String name = piece.name();
        if (name.endsWith("_HELMET")) {
            inventory.setHelmet(stack);
        } else if (name.endsWith("_CHESTPLATE")) {
            inventory.setChestplate(stack);
        } else if (name.endsWith("_LEGGINGS")) {
            inventory.setLeggings(stack);
        } else {
            inventory.setBoots(stack);
        }
    }

    /**
     * Consuming a Stupidness Protector and actually saving somebody, the way a totem would: a heal, the
     * regeneration and fire resistance the service asked for, a soft landing in case it was a fall, and a
     * shove that clears whatever was about to finish the rescue off.
     *
     * <p>The heal amount is not one of {@code SurvivalItemService}'s own parameters — {@code Rescue.save}
     * does not carry one — so it is this seam's own call, at the source's own default of four hearts: enough
     * that the save is not immediately undone by whatever is still nearby, not a full heal, because a
     * protector that fully restores somebody is a second medikit wearing a totem's name.
     */
    private SurvivalItemService.Rescue rescue() {
        return (holderId, regeneration, fireResistance, shoveRadius, shoveStrength) -> {
            Player holder = server.getPlayer(holderId);
            if (holder == null) {
                return false;
            }
            String key = SurvivalItemService.PLUGIN + ":" + SurvivalItemService.STUPIDNESS_PROTECTOR;
            if (!consumeOneCustomItem(holder, key)) {
                return false;
            }
            var maxHealth = holder.getAttribute(Attribute.MAX_HEALTH);
            double max = maxHealth == null ? 20.0 : maxHealth.getValue();
            holder.setHealth(Math.min(max, 8.0));
            holder.setFireTicks(0);
            holder.setRemainingAir(holder.getMaximumAir());
            if (!regeneration.isZero()) {
                holder.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                        (int) ticksOf(regeneration), 1));
            }
            if (!fireResistance.isZero()) {
                holder.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                        (int) ticksOf(fireResistance), 0));
            }
            // A soft landing in case it was a fall that nearly killed them — five seconds, the source's own
            // number for this rescue specifically (Leap's cushion is four; this one is not the same item).
            holder.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, false));
            if (shoveRadius > 0 && shoveStrength > 0) {
                Location centre = holder.getLocation();
                for (LivingEntity victim : enemiesNear(holder, shoveRadius)) {
                    Vector push = victim.getLocation().toVector().subtract(centre.toVector());
                    if (push.lengthSquared() < 1.0E-4) {
                        push = new Vector(1, 0, 0);
                    }
                    victim.setVelocity(push.setY(0.55).normalize().multiply(shoveStrength));
                }
            }
            core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_STUPIDNESS_PROTECTOR);
            holder.sendMessage(core.messages().get("hungergames.item-stupidness-saved"));
            return true;
        };
    }

    /** Takes one custom item with this key out of the holder's inventory. @return whether one was found. */
    private boolean consumeOneCustomItem(Player holder, String key) {
        var inventory = holder.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && core.itemFactory().is(stack, key)) {
                if (stack.getAmount() > 1) {
                    stack.setAmount(stack.getAmount() - 1);
                } else {
                    contents[i] = null;
                }
                inventory.setStorageContents(contents);
                return true;
            }
        }
        return false;
    }

    /**
     * Striking whatever is near the holder with one volley of the exmatrikulator's aura.
     *
     * <p>Plays {@code ITEM_EXMATRIKULATOR} from here, on a volley that actually caught somebody, rather
     * than at activation — {@code SurvivalItemService.useExmatrikulator} only records that an aura has
     * begun and calls no seam of its own (see that class's javadoc), so this is the only moment this item
     * has anything to make a noise about.
     */
    /** The two sentences the survival items say for themselves. */
    private SurvivalItemService.Voice itemVoice() {
        return new SurvivalItemService.Voice() {

            @Override
            public void unleashed(UUID holder, Duration forHowLong) {
                Player player = server.getPlayer(holder);
                if (player != null) {
                    player.sendMessage(core.messages().get("hungergames.item-exmatrikulator",
                            "seconds", String.valueOf(forHowLong.toSeconds())));
                }
            }

            @Override
            public void protectorIsPassive(UUID holder) {
                Player player = server.getPlayer(holder);
                if (player != null) {
                    player.sendMessage(core.messages().get("hungergames.item-stupidness-passive"));
                }
            }
        };
    }

    private SurvivalItemService.Volley volley() {
        return (holderId, radius, maxTargets, damage, fireDuration) -> {
            Player holder = server.getPlayer(holderId);
            if (holder == null) {
                return List.of();
            }
            List<UUID> struck = new ArrayList<>();
            int fired = 0;
            for (LivingEntity victim : enemiesNear(holder, radius)) {
                if (fired++ >= maxTargets) {
                    break;
                }
                Scheduling.region(plugin, victim.getLocation(),
                        () -> strikeChosenTarget(victim, holder, damage, fireDuration));
                struck.add(victim.getUniqueId());
            }
            if (!struck.isEmpty()) {
                core.effects().play(holder.getUniqueId(), HungerGamesCues.ITEM_EXMATRIKULATOR);
            }
            return struck;
        };
    }

    // ==================== loot ====================

    /**
     * The six loot tables the live tournament server was tuned on, defined only if a server has not
     * already written its own.
     *
     * <p>{@code defineIfAbsent} rather than {@code define}: the loot editor writes to these, and a module
     * that redefined them on every start would throw away whatever an owner had tuned on the last evening.
     * The entries themselves come from {@link LootDefaults} — see its javadoc for why they are six real
     * pools rather than the two empty ones this used to define, and for the tier each was given.
     */
    /**
     * Opens the HTTP admin API's socket, if {@code api.enabled} says so.
     *
     * <p>Everything on the other side of this call — every one of {@code HttpApiService}'s seven endpoint
     * groups and its socket — was written, and unit tested, and never once started: nothing anywhere in
     * this module constructed a {@code HttpApiService}. The settings page and the boot banner both showed
     * an address and a port, correctly, for an API that had never opened either. This is the line that was
     * missing — the same shape of bug as the session store, this whole class, and the four item services
     * before it: finished work nobody called.
     *
     * <p>Loot table editing is the one endpoint group that is honest about a real gap rather than papering
     * over it — see {@code LootCatalogueApiAdapter}'s class note for exactly what {@code LootEntry} does
     * not yet model.
     */
    private void startTheHttpApi() {
        this.apiSupport = new de.raindancer.modules.hungergames.service.ApiSupport(session, log, settings());
        var wiring = new de.raindancer.modules.hungergames.service.HttpApiService.Wiring(
                new de.raindancer.modules.hungergames.service.GameControlApiAdapter(
                        control, preflight, border, virtualTime, () -> borderPhases),
                new de.raindancer.modules.hungergames.service.DeathmatchApiAdapter(deathmatch),
                supplyDrops,
                sponsorBeacons,
                monsterWaves,
                new de.raindancer.modules.hungergames.service.SoundEffectsApiAdapter(core.effects()),
                new de.raindancer.modules.hungergames.service.LootCatalogueApiAdapter(lootTables, core.lootTables()),
                new de.raindancer.modules.hungergames.service.GamemastersApiAdapter(gamemasters),
                spectators,
                simulation,
                settingsStore);
        var router = de.raindancer.modules.hungergames.service.HttpApiService.route(apiSupport, wiring);
        this.httpApi = new de.raindancer.modules.hungergames.service.HttpApiService(plugin, apiSupport, log,
                router, de.raindancer.modules.hungergames.service.HttpApiService.viaScheduling(plugin,
                        de.raindancer.modules.hungergames.service.HttpApiService.SERVER_THREAD_TIMEOUT),
                key -> settingsStore.set("api.key", key), settings());
        httpApi.start();
        context.closeWith(httpApi::stop);
    }

    private void defineTheLootTables() {
        LootDefaults.all().values().forEach(table ->
                lootTables.defineIfAbsent(table.name(), table.tier(), table.fillPercent(), table.entries()));
    }

    /**
     * Fills every container within the configured radius of the middle, each from the table matching
     * <em>its own type</em> rather than one pool for the whole arena.
     *
     * <p>The plugin this module replaces filled every container — a starter chest at the rim and the
     * cornucopia's trapped chest alike — from the same pool, which threw away the entire reason
     * {@link LootDefaults} ships six tables instead of one: a common chest and a supply crate are not the
     * same risk, and should not carry the same odds of a diamond sword. The mapping below is the old
     * plugin's own {@code ContainerType.fromMaterial}, read the same order it checked in — trapped chest
     * before plain chest, so a trapped chest is never matched as an ordinary one — but pointed at this
     * module's table names instead of an enum. A container type with no table of its own, or one this
     * module has not met yet, falls back to {@code "chest"}: the ordinary tier, not an empty container.
     *
     * <p>Through Core's {@code LootFiller}, which owns the weighted roll and the custom-item lookup. This
     * only decides <em>which</em> containers, and <em>which table</em> for each: everything inside
     * {@code loot.scan-radius} horizontally and {@code loot.scan-y-range} vertically, which is the arena
     * and deliberately not the whole world.
     */
    private int fillTheArena(ArenaLayout layout) {
        World world = server.getWorld(layout.world());
        if (world == null) {
            return 0;
        }
        HungerGamesSettings now = settings();
        int radius = now.lootScanRadius();
        int height = now.lootScanYRange();
        Random random = new Random();
        int filled = 0;

        for (int x = layout.centreX() - radius; x <= layout.centreX() + radius; x++) {
            for (int z = layout.centreZ() - radius; z <= layout.centreZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;   // nothing is generated to place loot in
                }
                for (int y = layout.centreY() - height; y <= layout.centreY() + height; y++) {
                    var block = world.getBlockAt(x, y, z);
                    var state = block.getState(false);
                    if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
                        String type = block.getType().name();
                        String tableName;
                        if (type.contains("TRAPPED_CHEST")) {
                            tableName = "trapped-chest";
                        } else if (type.contains("COPPER") && type.contains("CHEST")) {
                            tableName = "copper-chest";
                        } else if (type.equals("CHEST")) {
                            tableName = "chest";
                        } else if (type.equals("BARREL")) {
                            tableName = "barrel";
                        } else if (type.contains("SHELF") || type.contains("BOOKSHELF")) {
                            tableName = "shelf";
                        } else {
                            tableName = "chest";
                        }
                        Optional<LootTable> table = lootTables.byName(tableName)
                                .or(() -> lootTables.byName("chest"));
                        if (table.isPresent()) {
                            core.lootFiller().fill(holder.getInventory(), table.get(), random);
                            filled++;
                        }
                    }
                }
            }
        }
        return filled;
    }

    /** One supply crate's contents. */
    private void fillCrate(Chest crate, String tableName) {
        lootTables.byName(tableName)
                .ifPresent(table -> core.lootFiller().fill(crate.getInventory(), table, new Random()));
    }

    // ==================== timetables ====================

    private List<BorderPhaseConfig> loadBorderPhases() {
        try {
            return borderPhaseStore.load();
        } catch (RuntimeException unreadable) {
            log.warn("The border phases could not be read ({}); the border will not move this round.",
                    unreadable.getMessage());
            return List.of();
        }
    }

    /**
     * When supply drops land.
     *
     * <p>Evenly through the round's second half rather than read from a file, because the file the source
     * kept them in was a list of absolute minute marks that every change to the round length invalidated
     * silently. Derived, so a round shortened to twenty minutes gets its drops in the right places.
     */
    private List<Duration> supplyDropTimes() {
        if (!settings().supplyDropsEnabled()) {
            return List.of();
        }
        Duration round = settings().roundDuration();
        List<Duration> times = new ArrayList<>();
        // Three drops, in the second half: the first once the scramble has settled, the last with enough
        // time left for somebody to actually reach it.
        for (int i = 1; i <= 3; i++) {
            times.add(round.multipliedBy(40 + i * 15).dividedBy(100));
        }
        return List.copyOf(times);
    }

    /** When sponsor beacons appear. Later than the drops, so the two are separate events. */
    private List<Duration> beaconTimes() {
        Duration round = settings().roundDuration();
        return List.of(round.multipliedBy(35).dividedBy(100), round.multipliedBy(70).dividedBy(100));
    }

    /** How long the round has been going, across a restart, according to the configured policy. */
    private Duration elapsedAcrossTheRestart() {
        Optional<Long> since = session.runningSinceMillis();
        if (since.isEmpty()) {
            return Duration.ZERO;
        }
        Duration wallClock = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - since.get()));
        return settings().offlineTimePolicy() == HungerGamesSettings.OfflineTimePolicy.COUNT
                ? wallClock
                // PAUSE: the downtime did not happen as far as the round is concerned. Without a record of
                // how long the server was down, the honest answer is to resume where the timer last was —
                // and the timer's own state is what virtualTime holds.
                : virtualTime.elapsed();
    }

    // ==================== what happens at the end ====================

    /** The grace period ending, announced to everybody it was protecting. */
    private void gracePeriodEnded(Collection<UUID> stillProtected) {
        broadcast("hungergames.grace-end");
    }

    /**
     * One line to the whole server, through whichever channels the settings have switched on.
     *
     * <p>{@code null} for the player, because a broadcast has no subject — {@code AnnouncementService} takes
     * one so that a line addressed to somebody can also reach their action bar, and a server-wide line is
     * exactly the case where there is nobody to address.
     */
    private void broadcast(String key, Object... values) {
        announcements.send(null, server, key,
                new AnnouncementService.Style[] {
                        AnnouncementService.Style.CHAT, AnnouncementService.Style.TITLE},
                values);
    }

    /**
     * The round is over: everybody out of survival, the border back, the arena left standing.
     *
     * <p>The arena is deliberately not demolished. Whoever ran the tournament wants to walk through it
     * afterwards, and the next {@code /init} rebuilds over the top of it anyway.
     */
    private void finishTheRound(Winner winner) {
        for (Player player : server.getOnlinePlayers()) {
            if (session.isWhitelisted(player.getUniqueId())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
        border.resetToInitial();
        timer.stop();
        sponsorBeacons.resetForNewRound();
        log.info("The round is over. The arena is left standing; run /init for the next one.");
    }

    /**
     * Everybody who is still alive, gathered on the middle, for the deathmatch.
     *
     * <p>{@link DeathmatchService#execute()} calls this as a plain collaborator, on whatever thread reached
     * {@code execute()} — a gamemaster-triggered call may arrive from a command, a menu click or the HTTP
     * API's own global-thread hop, and an automatic trigger arrives from wherever the round's own clock
     * ticks. None of those is guaranteed to be the region a given tribute is currently standing in, and a
     * deathmatch can easily be pulling players together from several different regions of the border at
     * once — teleporting several of them in a loop is exactly the "crosses region borders" case
     * {@code Scheduling.entity} exists for: it hops onto whichever thread actually owns that one tribute
     * right now, rather than assuming the calling thread does.
     */
    private void gatherEverybodyInTheMiddle(HungerGamesSettings now) {
        Optional<Location> middle = arena.centre();
        if (middle.isEmpty()) {
            log.warn("The deathmatch could not gather anybody: there is no arena.");
            return;
        }
        if (!now.deathmatchTeleportToCenter()) {
            return;
        }
        Location target = middle.get().clone().add(0, now.deathmatchTeleportYOffset(), 0);
        for (UUID uuid : session.participants().alive()) {
            Player tribute = server.getPlayer(uuid);
            if (tribute != null) {
                Scheduling.entity(plugin, tribute, () -> tribute.teleport(target));
            }
        }
    }

    // ==================== the round's clock running out ====================

    /** Who is asked whether an over-running round should be ended: whoever could end it themselves. */
    private Collection<UUID> whoCanDecide() {
        return server.getOnlinePlayers().stream()
                .filter(player -> de.raindancer.modules.hungergames.util.PermissionNodes
                        .mayOpenTheAdminSuite(player))
                .map(Player::getUniqueId)
                .toList();
    }

    /**
     * Asking an operator, in chat, whether the round should end now.
     *
     * <p>Core's {@code ChatButtons}, so the two answers are clickable rather than commands somebody has to
     * type while a tournament waits. The window is the extension itself: an unanswered question is the same
     * as "extend", which is what {@code RoundExpiryService} does with no answer at all.
     */
    private RoundExpiryService.Prompt expiryPrompt() {
        return (who, overrun, end, extend) -> {
            Player asked = server.getPlayer(who);
            if (asked == null) {
                return;
            }
            asked.sendMessage(core.messages().get("hungergames.round-overrun",
                    "overrun", de.raindancer.core.world.time.Times.describe(overrun)));
            asked.sendMessage(core.buttons().ask(who, RoundExpiryService.EXTENSION,
                    ignored -> end.run(), ignored -> extend.run()));
        };
    }

    // ==================== small adapters the screens need ====================

    private de.raindancer.modules.hungergames.service.LobbyBoxService lobbyBox() {
        var lobby = new de.raindancer.modules.hungergames.service.LobbyBoxService(session,
                () -> arena.layout().map(layout -> new de.raindancer.modules.hungergames.service
                        .LobbyBoxService.Box(
                        new de.raindancer.modules.hungergames.service.LobbyBoxService.Point(
                                layout.world(), layout.centreX(), layout.centreY(), layout.centreZ()),
                        new de.raindancer.modules.hungergames.service.LobbyBoxService.Point(
                                layout.world(), layout.lobbyCentre().x(), layout.lobbyCentre().y(),
                                layout.lobbyCentre().z()))));
        lobby.settings(settings());
        return lobby;
    }

    private PreflightCheckService.SupplyDropPlan supplyDropPlan() {
        return new PreflightCheckService.SupplyDropPlan() {
            @Override
            public List<Duration> schedule() {
                return supplyDropTimes();
            }

            @Override
            public String lootTableName() {
                return SUPPLY_LOOT;
            }
        };
    }

    private PreflightCheckService.SponsorShopStatus sponsorShopStatus() {
        return new PreflightCheckService.SponsorShopStatus() {
            @Override
            public boolean enabled() {
                return sponsorTokens.tokensEnabled();
            }

            @Override
            public Optional<String> validationError() {
                List<String> problems = shopStore.problems();
                return problems.isEmpty() ? Optional.empty() : Optional.of(problems.get(0));
            }
        };
    }

    /** The screens, with everything they need. See {@link HungerGamesScreens}. */
    private IHungerGamesScreensOpener screens() {
        return new HungerGamesScreens(new HungerGamesScreens.Wiring(
                brand, session, settingsStore::current, () -> TeamRules.from(settings()), () -> borderPhases,
                control, preflight, deathmatch, supplyDrops, monsterWaves, simulation, spectators,
                sponsorTokens, roundLog, virtualTime,
                shopStore, announcements, gamemasters, teamBadgeChooser(), teamCaptainChooser(),
                core.prompts(), core.items(),
                core.itemFactory(), plainStack(), roster, core.effects(),
                // Cues live in Core's registry and Core persists them; nothing extra to write here. Named
                // rather than passed as null so the page's own "did that save?" question has an answer.
                () -> { }, context.chat(), core.settingsNavigation(),
                applied -> {
                    borderPhaseStore.save(applied.settings().phases());
                    borderPhases = applied.settings().phases();
                    log.info("The border phases were rewritten from the conflict screen.");
                },
                log));
    }

    /**
     * Picking a team's badge — Core's own item chooser, so a badge is always something the server actually
     * has an icon for.
     */
    private de.raindancer.modules.hungergames.screen.TeamIdentityMenu.BadgeChooser teamBadgeChooser() {
        return (viewer, returnTo, chosen) ->
                new de.raindancer.core.ui.choose.ItemChooser(viewer, brand, returnTo,
                        "Pick a badge", chosen).open();
    }

    /**
     * Picking a captain out of a team's own members — Core's player chooser, narrowed to exactly that team
     * rather than the whole server, because a captain who is not on the team is not a captain of anything.
     */
    private de.raindancer.modules.hungergames.screen.TeamIdentityMenu.CaptainChooser teamCaptainChooser() {
        return (viewer, returnTo, among, chosen) -> {
            de.raindancer.core.ui.choose.PlayerDirectory directory =
                    new de.raindancer.core.ui.choose.PlayerDirectory(
                            () -> among.stream()
                                    .map(uuid -> new de.raindancer.core.ui.choose.PlayerEntry(uuid,
                                            nameOf(uuid), server.getPlayer(uuid) != null, 0L))
                                    .toList(),
                            System::currentTimeMillis);
            new de.raindancer.core.ui.choose.PlayerChooser(viewer, brand, returnTo, "Pick a captain",
                    directory, List.of(), entry -> chosen.accept(entry.id())).open();
        };
    }

    /** A stack for a reward Core's registries do not own — a material, or a potion. */
    private ShopMenu.PlainStack plainStack() {
        return (reward, amount) -> switch (reward) {
            // Icons owns building a named stack on this server; the amount is the one thing it does not
            // carry, because an icon is always one of something and a purchase is not.
            case SponsorShopStore.MaterialReward material -> {
                ItemStack stack = de.raindancer.core.ui.menu.Icons.of(material.material(), null, List.of());
                stack.setAmount(Math.max(1, amount));
                yield stack;
            }
            case SponsorShopStore.PotionReward potion -> {
                ItemStack stack = de.raindancer.core.ui.menu.Icons.of(
                        potion.variant().material(), null, List.of());
                stack.setAmount(Math.max(1, amount));
                if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
                    var type = org.bukkit.Registry.POTION
                            .get(org.bukkit.NamespacedKey.minecraft(
                                    potion.potionType().toLowerCase(java.util.Locale.ROOT)));
                    if (type != null) {
                        meta.setBasePotionType(type);
                        stack.setItemMeta(meta);
                    }
                }
                yield stack;
            }
            // The other two variants are Core's to build: a custom item comes from CustomItems and an
            // effect is not an item at all. A visible gap rather than an exception, because a shop entry
            // that cannot be drawn should be one blank slot on the page, not a page that will not open —
            // and Icons.filler is what every other blank slot on this server is.
            default -> de.raindancer.core.ui.menu.Icons.filler(Material.AIR);
        };
    }

    // ==================== the phase watcher ====================

    /**
     * The one subscriber that is written here rather than being a class of its own.
     *
     * <p>It exists to forward a phase change to the four services that need to know about one, and every
     * method it does not use is a no-op. As a file it would be a hundred lines of empty overrides around
     * four real ones — and the four real ones are the whole content.
     */
    private de.raindancer.modules.hungergames.store.GameEvents phaseWatcher() {
        return new de.raindancer.modules.hungergames.store.GameEvents() {
            @Override
            public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
                opTracker.onPhaseChanged(oldPhase, newPhase);
                if (newPhase == GamePhase.RUNNING) {
                    virtualTime.start();
                    timer.start();
                    supplyDrops.start();
                    roundExpiry.reset();
                    hermesBoots.resetForNewRound();
                }
            }

            @Override
            public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
                opTracker.onEliminated(participant);
            }

            @Override
            public void participantRevived(UUID participant) {
                opTracker.onRevived(participant);
            }

            @Override
            public void whitelistChanged(UUID player, boolean added) {
                // Nothing to do: the register is the whitelist, and it has already changed.
            }

            @Override
            public void teamCreated(de.raindancer.core.social.team.Team team) {
                // Nothing here. The team screens redraw themselves; presentation follows membership.
            }

            @Override
            public void teamDeleted(de.raindancer.core.social.team.Team team) {
                team.members().forEach(presentation::hide);
            }

            @Override
            public void teamColourChanged(de.raindancer.core.social.team.Team team,
                                          TeamColour oldColour, TeamColour newColour) {
                team.members().forEach(presentation::show);
            }

            @Override
            public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam,
                                              MembershipCause cause) {
                presentation.show(player);
            }

            @Override
            public void kill(UUID killer, UUID victim, int killerTotalKills) {
                // The announcement listener says it; nothing else needs to act on it.
            }

            @Override
            public void winnerDeclared(Winner winner) {
                // WinnerFinishListener holds the curtain and then finishes. Nothing else here.
            }
        };
    }

    // ==================== told ====================

    private ArenaBuildService.Told arenaTold() {
        return new ArenaBuildService.Told() {
            @Override
            public void building(UUID who, int platforms) {
                tell(who, "hungergames.arena-building", "platforms", String.valueOf(platforms));
            }

            @Override
            public void completed(UUID who, ArenaLayout layout) {
                tell(who, "hungergames.arena-built", "where", layout.describe());
            }

            @Override
            public void failed(UUID who, String why) {
                tell(who, "hungergames.arena-failed", "why", why);
            }
        };
    }

    private StartupSequenceService.Told startupTold() {
        return new StartupSequenceService.Told() {
            @Override
            public void underground(UUID who, int tributes) {
                tell(who, "hungergames.startup-underground", "tributes", String.valueOf(tributes));
            }

            @Override
            public void ready(UUID who, int tributes) {
                tell(who, "hungergames.startup-ready", "tributes", String.valueOf(tributes));
            }

            @Override
            public void refused(UUID who, String why) {
                tell(who, "hungergames.step-refused", "step", "startup", "why", why);
            }
        };
    }

    private CountdownService.Told countdownTold() {
        return new CountdownService.Told() {
            @Override
            public void counting(UUID who, int seconds) {
                roundLog.log("ROUND", "The countdown started: " + seconds + "s");
            }

            @Override
            public void begun(UUID who) {
                roundLog.log("ROUND", "The round began");
            }

            @Override
            public void refused(UUID who, String why) {
                tell(who, "hungergames.step-refused", "step", "start", "why", why);
            }
        };
    }

    /**
     * One line to whoever asked, if they are still there.
     *
     * <p>Silently nothing when they are not, which is the ordinary case for a console-run {@code /init} and
     * for anybody who logged out during a build that takes several seconds.
     */
    private void tell(UUID who, String key, Object... values) {
        Player player = who == null ? null : server.getPlayer(who);
        if (player != null) {
            core.messages().send(player, key, values);
        }
    }

    // ==================== reload ====================

    /**
     * A settings reload reaching everything that reads one.
     *
     * <p>Every service in this module implements {@code settings(HungerGamesSettings)} precisely so this
     * method can exist — see {@code PackageGrammarTest.everyServiceTakesTheSettings}. The one forgotten here
     * is the one that keeps yesterday's numbers for the rest of the tournament.
     */
    private void settingsChanged(HungerGamesSettings now) {
        arena.settings(now);
        border.settings(now);
        deathmatch.settings(now);
        opTracker.settings(now);
        announcements.settings(now);
        sponsorTokens.settings(now);
        sponsorBeacons.settings(now);
        supplyDrops.settings(now);
        monsterWaves.settings(now);
        simulation.settings(now);
        timer.settings(now);
        roundExpiry.settings(now);
        startup.settings(now);
        countdown.settings(now);
        control.settings(now);
        preflight.settings(now);
        arenaItems.settings(now);
        combatItems.settings(now);
        mobilityItems.settings(now);
        hermesBoots.settings(now);
        survivalItems.settings(now);
        medikitCountdown.settings(now);
        chatChannels.settings(now);
        if (apiSupport != null) {
            apiSupport.settings(now);
        }
        if (httpApi != null) {
            httpApi.settings(now);
        }
        if (hotbar != null) {
            hotbar.settings(now);
        }
        borderPhases = loadBorderPhases();
        log.info("The settings were reloaded and passed to every service that reads them.");
    }

    /** Every service, told its settings for the first time. Called once, from the module. */
    public void applySettingsNow() {
        settingsChanged(settings());
    }

    // ==================== gamemasters ====================

    /**
     * The gamemaster roster and mode.
     *
     * <p>Written here as a small class rather than in {@code service/} because it is exactly the join of two
     * things that already exist — {@link GamemasterStore}, which is the roster on disk, and a permission
     * node — and a service whose whole body is "ask the store, or ask the permission" is a service that
     * exists to be a file.
     */
    private final class Gamemasters implements GamemasterMenu.Gamemasters {

        private final java.util.Map<UUID, GamemasterStore.ActiveState> active;

        private Gamemasters() {
            this.active = new java.util.concurrent.ConcurrentHashMap<>(gamemasterStore.load());
        }

        @Override
        public List<String> names() {
            return active.keySet().stream().map(HungerGamesWiring.this::nameOf).sorted().toList();
        }

        @Override
        public Set<UUID> onlineActive() {
            return active.keySet().stream()
                    .filter(uuid -> server.getPlayer(uuid) != null)
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public boolean isGamemaster(UUID uuid) {
            HungerGamesSettings now = settings();
            boolean byList = active.containsKey(uuid);
            Player player = server.getPlayer(uuid);
            boolean byNode = player != null && player.hasPermission(
                    de.raindancer.modules.hungergames.util.PermissionNodes.GAMEMASTER);

            return switch (now.gamemasterPermissionMode()) {
                case PERMISSION -> byNode;
                case LIST -> byList;
                case BOTH -> byNode || byList;
            };
        }

        @Override
        public boolean isActive(UUID uuid) {
            return active.containsKey(uuid);
        }

        @Override
        public Optional<String> activate(Player player) {
            if (!settings().gamemasterEnabled()) {
                return Optional.of("gamemaster mode is switched off");
            }
            if (active.containsKey(player.getUniqueId())) {
                return Optional.of("you are already a gamemaster");
            }
            // What they were is remembered before it is changed, which is what makes deactivating put them
            // back rather than guess at survival.
            active.put(player.getUniqueId(),
                    new GamemasterStore.ActiveState(player.getGameMode(), false));
            gamemasterStore.save(active);
            setMode(player, settings().gamemasterDefaultMode()
                    == HungerGamesSettings.GamemasterMode.CREATIVE
                    ? GameMode.CREATIVE : GameMode.SPECTATOR);
            roundLog.log("ADMIN", player.getName() + " became a gamemaster");
            return Optional.empty();
        }

        @Override
        public Optional<String> deactivate(Player player) {
            GamemasterStore.ActiveState was = active.remove(player.getUniqueId());
            if (was == null) {
                return Optional.of("you are not a gamemaster");
            }
            gamemasterStore.save(active);
            setMode(player, was.previousMode() == null ? GameMode.SURVIVAL : was.previousMode());
            roundLog.log("ADMIN", player.getName() + " stopped being a gamemaster");
            return Optional.empty();
        }

        @Override
        public void setMode(Player player, GameMode mode) {
            player.setGameMode(mode);
        }

        /**
         * Puts a name on the roster.
         *
         * <p>By name rather than by UUID because this is filled in before an evening, from a list, for people
         * who may never have been on this server — the same reason {@code /allow} takes names. The UUID is
         * derived the same way, so the two agree about who somebody is before they first join.
         */
        @Override
        public List<String> addName(String actor, String name) {
            if (name == null || name.isBlank()) {
                return List.of("no name was given");
            }
            UUID uuid = uuidForName(name);
            if (active.containsKey(uuid)) {
                return List.of(name + " is already a gamemaster");
            }
            active.put(uuid, new GamemasterStore.ActiveState(null, false));
            gamemasterStore.save(active);
            roundLog.log("ADMIN", actor + " added " + name + " as a gamemaster");
            return List.of();
        }

        @Override
        public List<String> removeName(String actor, String name) {
            if (name == null || name.isBlank()) {
                return List.of("no name was given");
            }
            UUID uuid = uuidForName(name);
            // Both spellings: somebody added before their first join is keyed by the derived UUID, and
            // somebody who has since played is keyed by their real one.
            Player online = server.getPlayerExact(name);
            boolean removed = active.remove(uuid) != null;
            if (online != null) {
                removed |= active.remove(online.getUniqueId()) != null;
            }
            if (!removed) {
                return List.of(name + " is not a gamemaster");
            }
            gamemasterStore.save(active);
            roundLog.log("ADMIN", actor + " removed " + name + " as a gamemaster");
            return List.of();
        }

        /** Whoever that name is: their real UUID if they are here, otherwise a stable derived one. */
        private UUID uuidForName(String name) {
            Player online = server.getPlayerExact(name);
            if (online != null) {
                return online.getUniqueId();
            }
            return UUID.nameUUIDFromBytes(
                    ("hungergames:" + name.toLowerCase(java.util.Locale.ROOT))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
