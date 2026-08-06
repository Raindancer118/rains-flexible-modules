package de.raindancer.modules.hungergames;

import de.raindancer.core.RainsCore;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.spawn.Spawner;
import de.raindancer.core.world.spawn.Spawns;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.hungergames.listener.AdminHotbarListener;
import de.raindancer.modules.hungergames.listener.AnnouncementListener;
import de.raindancer.modules.hungergames.listener.ConnectionListener;
import de.raindancer.modules.hungergames.listener.EliminationListener;
import de.raindancer.modules.hungergames.listener.LobbyListener;
import de.raindancer.modules.hungergames.listener.PortalListener;
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
import de.raindancer.modules.hungergames.service.ArenaSites;
import de.raindancer.modules.hungergames.service.BorderService;
import de.raindancer.modules.hungergames.service.CountdownService;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.GameTimerService;
import de.raindancer.modules.hungergames.service.HungerGamesCues;
import de.raindancer.modules.hungergames.service.MannequinSimService;
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
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
    private final TeamPresentationService presentation;
    private final OpTrackerService opTracker;
    private final AnnouncementService announcements;
    private final SponsorTokenService sponsorTokens;
    private final SponsorBeaconService sponsorBeacons;
    private final SupplyDropService supplyDrops;
    private final MonsterWaveService monsterWaves;
    private final MannequinSimService simulation;
    private final GameTimerService timer;
    private final RoundExpiryService roundExpiry;
    private final StartupSequenceService startup;
    private final CountdownService countdown;
    private final GameControlService control;
    private final PreflightCheckService preflight;
    private final Gamemasters gamemasters;
    private AdminHotbarListener hotbar;

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
                session, control, preflight, deathmatch, settingsStore::current, screens);
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
                shopStore, announcements, gamemasters, core.prompts(), core.items(),
                core.itemFactory(), plainStack(), roster,
                applied -> {
                    borderPhaseStore.save(applied.settings().phases());
                    borderPhases = applied.settings().phases();
                    log.info("The border phases were rewritten from the conflict screen.");
                },
                log));
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
