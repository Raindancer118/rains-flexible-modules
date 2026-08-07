package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.TributeRoster;
import de.raindancer.modules.hungergames.visual.BarrierRing;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code /startup}: the launch sequence, which is the part of a tournament a crowd actually watches.
 *
 * <h2>What happens, and why it is in this order</h2>
 * <ol>
 *   <li><b>Tributes are taken underground</b>, one to the foot of each tube, facing the middle, in adventure
 *       mode. Saturation so nobody starves waiting, and a negative jump boost so nobody can hop out of the
 *       ring around them.</li>
 *   <li><b>A barrier ring goes up at head height</b> around each of them. Eight blocks, middle left open —
 *       see {@link BarrierRing} for why that middle block is load-bearing.</li>
 *   <li><b>The room's lights come on</b> from the centre outwards, one ring of redstone lamps at a time.</li>
 *   <li><b>Dropped items are cleared</b>, so a previous round's litter is not lying in the cornucopia.</li>
 *   <li><b>Tributes are lifted</b>, one at a time, {@code startup.player-levitation-delay} ticks apart. The
 *       platform's centre block is taken out just before each one launches and put back the instant they
 *       arrive, so a tribute already standing up there cannot fall back down somebody else's shaft.</li>
 *   <li><b>Once everybody is up</b>: the underground rings come down, new rings go up around the platforms,
 *       the air above each platform is cleared, the loot is placed, and the lobby is demolished.</li>
 * </ol>
 *
 * <h2>Three ways this used to hang forever, all of them fixed here</h2>
 * The sequence only ends when every tribute has arrived, so anything that stops one arriving stops the round.
 * The source counted arrivals in a mutable field on a non-final class and had three holes in it:
 *
 * <ul>
 *   <li><b>A disconnect mid-flight.</b> Handled: the watcher notices they are gone, seals their platform and
 *       counts them, because a tribute who logged out at the wrong second must not cost everybody the round.</li>
 *   <li><b>A tribute who never reaches the platform</b> — snagged on the tube, killed by something, teleported
 *       away by another plugin. Handled by the {@link #ARRIVAL_TIMEOUT}, after which they are counted anyway
 *       and the console says so.</li>
 *   <li><b>Two tributes arriving on the same tick.</b> Not handled in the source, which incremented an
 *       {@code int} from a task per player; here the counter is an {@link AtomicInteger} and the completion is
 *       claimed exactly once, so the READY step cannot run twice — which is what would demolish the lobby
 *       twice and place two barrier rings on every platform.</li>
 * </ul>
 *
 * <h2>The test-simulation path</h2>
 * With nobody real online but tributes registered, the tube sequence is skipped entirely and the round goes
 * straight to READY. That is what makes a dry run possible the day before, with mannequins standing in — and
 * it is the source's own behaviour, kept deliberately rather than treated as a bug.
 */
public final class StartupSequenceService implements IHungerGamesService {

    /** How long a tribute is given to reach their platform before the sequence gives up on them. */
    public static final long ARRIVAL_TIMEOUT_TICKS = 20L * 30L;

    /** How long levitation is applied for. Comfortably longer than the timeout, and removed on arrival. */
    private static final int LEVITATION_TICKS = 20 * 30;

    /**
     * The world time the sequence sets: just before sunset.
     *
     * <p>Every round therefore begins at the same hour, whatever time it was when the arena was built — and
     * the launch happens in the light with the sun going down, which is the whole look of it. {@code /start}
     * sets it again at the end of the countdown, because the sequence itself takes a minute or two.
     */
    public static final long SUNSET = 22_500L;

    /** How far above the platform a tribute counts as having arrived. */
    private static final double ARRIVAL_MARGIN = 1.5;

    /**
     * How many blocks above the platform's own level the temporary arrival ceiling sits.
     *
     * <p>Comfortably above {@link #ARRIVAL_MARGIN} — a standing tribute is under two blocks tall, so four
     * blocks of headroom means the tick loop below always recognises them as arrived before their head
     * could ever reach this ceiling. It exists as the physical backstop the flight actually stops against,
     * not as the thing that decides when they have arrived; that is still the Y check it always was.
     */
    private static final int CEILING_CLEARANCE_BLOCKS = 4;

    /** The delay before loot is placed. One second, so chunks and their containers have settled first. */
    private static final long LOOT_DELAY_TICKS = 20L;

    /** Whoever is connected right now. */
    @FunctionalInterface
    public interface OnlinePlayers {
        List<Player> all();
    }

    /** Filling the arena's containers — {@code LootTables} does the work, this is only when. */
    @FunctionalInterface
    public interface LootFilling {
        int fillTheArena(ArenaLayout arena);
    }

    /** Told what happened, in words somebody watching the console reads. */
    public interface Told {

        void underground(UUID who, int tributes);

        void ready(UUID who, int tributes);

        void refused(UUID who, String why);
    }

    private final Plugin plugin;
    private final GameSession session;
    private final ArenaBuildService arena;
    private final OnlinePlayers online;
    private final LootFilling loot;
    private final Effects effects;
    private final MannequinSimService simulation;
    private final Told told;
    private final LogChannel log;

    private volatile HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    /** What was under each platform's middle before it was opened, so it can be put back exactly. */
    private final Map<String, Material> openedPlatforms = new LinkedHashMap<>();

    /** Where the underground rings went up, so they can come down again without guessing. */
    private final List<ArenaLayout.Stand> undergroundRings = new ArrayList<>();

    private final AtomicInteger arrived = new AtomicInteger();
    private volatile int expected;
    private volatile boolean readyClaimed;

    public StartupSequenceService(Plugin plugin, GameSession session, ArenaBuildService arena,
                                  OnlinePlayers online, LootFilling loot, Effects effects,
                                  MannequinSimService simulation, Told told, LogChannel log) {
        this.plugin = plugin;
        this.session = session;
        this.arena = arena;
        this.online = online;
        this.loot = loot;
        this.effects = effects;
        this.simulation = simulation;
        this.told = told;
        this.log = log;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** The {@code /startup} stage, in the shape {@link GameControlService.Stage} takes. */
    public GameControlService.Stage startupStage() {
        return this::run;
    }

    /** Whether the sequence is mid-flight — for a screen that wants to grey out its own button. */
    public boolean isRunning() {
        return session.phase() == GamePhase.STARTUP;
    }

    /** How far along it is, for the same screen. */
    public String progress() {
        return arrived.get() + "/" + expected;
    }

    // ==================== the sequence ====================

    /** Runs it. Returns whether it started; the outcome arrives through {@link Told}. */
    public boolean run(UUID actor) {
        Optional<ArenaLayout> maybe = arena.layout();
        if (maybe.isEmpty()) {
            told.refused(actor, "there is no arena — run /init first");
            return false;
        }
        ArenaLayout layout = maybe.get();
        World world = worldOf(layout);
        if (world == null) {
            told.refused(actor, "the world the arena was built in ('" + layout.world() + "') is not loaded");
            return false;
        }

        List<Player> tributes = tributesOnline();
        if (tributes.isEmpty()) {
            // Nobody real, but tributes on the books: a dry run. Straight to READY, and the mannequins are
            // put on their platforms by whatever is watching the phase.
            if (session.participants().all().size() >= GameControlService.MIN_PLAYERS) {
                if (!session.transitionTo(GamePhase.STARTUP)) {
                    told.refused(actor, "the round would not move into STARTUP from " + session.phase());
                    return false;
                }
                log.info("No tributes are online — running the start-up as a simulation, with no tube "
                        + "sequence.");
                expected = 0;
                arrived.set(0);
                reachReady(actor, layout, world);
                return true;
            }
            told.refused(actor, "no tribute is online and none are registered — add at least one with "
                    + "/allow <name>, or paste a list into " + TributeRoster.FILE_NAME);
            return false;
        }

        if (!session.transitionTo(GamePhase.STARTUP)) {
            told.refused(actor, "the round would not move into STARTUP from " + session.phase());
            return false;
        }

        List<Player> inOrder = byTeam(tributes);
        expected = Math.min(inOrder.size(), layout.platformCount());
        arrived.set(0);
        readyClaimed = false;
        openedPlatforms.clear();
        undergroundRings.clear();

        if (inOrder.size() > layout.platformCount()) {
            // Said out loud rather than silently dropping people. The arena was built for a count, more
            // tributes turned up, and somebody has to know before the round rather than after it.
            log.warn("{} tributes are online but the arena has only {} platforms — the last {} will not be "
                            + "placed. Rebuild with /init for the real count.",
                    inOrder.size(), layout.platformCount(), inOrder.size() - layout.platformCount());
        }

        takeThemUnderground(inOrder, layout, world);
        told.underground(actor, expected);

        lightTheRoom(layout, world);
        // world.getEntities() walks every entity in the whole world, which spans as many regions as the
        // world has — not just wherever run(actor) itself happened to be called from (a command's sender,
        // an HTTP request already hopped to the global thread, or a menu click on the entity thread of
        // whoever clicked). There is no single region that owns "every entity in the world", so this is
        // exactly the world/server-wide case Scheduling.global exists for, rather than Scheduling.region,
        // which only pins to one location's region.
        Scheduling.global(plugin, () -> clearDroppedItems(world));

        Scheduling.globalLater(plugin, Math.max(1, settings.startupLevitationStartDelay()),
                () -> launchThemAll(inOrder, layout, world, actor));
        return true;
    }

    /** One tribute per tube, facing the middle, held in place by a ring. */
    private void takeThemUnderground(List<Player> tributes, ArenaLayout layout, World world) {
        for (int i = 0; i < expected; i++) {
            Player tribute = tributes.get(i);
            ArenaLayout.Stand start = layout.undergroundStarts().get(i);
            Location target = new Location(world, start.x(), start.y(), start.z(), start.yaw(), 0f);
            // Looking at the middle rather than merely carrying the platform's yaw: the two agree, and
            // setting the direction is what makes it true even if the stored yaw was ever wrong.
            target.setDirection(new Location(world, layout.centreX() + 0.5, start.y(), layout.centreZ() + 0.5)
                    .toVector().subtract(target.toVector()));

            tribute.teleport(target);
            tribute.setGameMode(GameMode.ADVENTURE);
            hold(tribute);

            BarrierRing.place(world, start.blockX(), start.blockY(), start.blockZ());
            undergroundRings.add(start);
        }
        log.info("{} tribute(s) are at the foot of their tubes.", expected);
    }

    /**
     * Saturation and a negative jump boost.
     *
     * <p>Saturation so a wait of several minutes does not start the round with everybody's hunger bar
     * already draining. The jump boost is {@code -128}, which is not a small nudge downwards — it is what
     * removes jumping altogether, and it is the difference between a ring that holds somebody and a ring
     * they hop over.
     */
    private void hold(Player tribute) {
        tribute.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION,
                0, false, false, true));
        tribute.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, PotionEffect.INFINITE_DURATION,
                -128, false, false, false));
    }

    /** The ceiling's lamps, ring by ring from the middle outwards. */
    private void lightTheRoom(ArenaLayout layout, World world) {
        int gap = Math.max(1, settings.startupLampDelay());
        Location centre = new Location(world, layout.centreX(), layout.roomCeilingY(), layout.centreZ());

        for (int ring = 0; ring <= arena.ceilingRings(); ring++) {
            int thisRing = ring;
            Scheduling.globalLater(plugin, 1L + (long) ring * gap, () -> {
                if (arena.lightTheCeiling(thisRing, true) > 0) {
                    effects.playAt(world.getName(), centre.getX(), centre.getY(), centre.getZ(),
                            HungerGamesCues.STARTUP_LAMP);
                }
            });
        }
    }

    /** Somebody else's litter, and a previous round's. */
    private void clearDroppedItems(World world) {
        int removed = 0;
        for (var entity : world.getEntities()) {
            if (entity instanceof Item) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleared {} dropped item(s) from the arena.", removed);
        }
    }

    /** Every tribute launched, staggered, so the crowd sees them arrive one by one. */
    private void launchThemAll(List<Player> tributes, ArenaLayout layout, World world, UUID actor) {
        // Every round begins at the same hour. See SUNSET.
        world.setTime(SUNSET);

        int gap = Math.max(0, settings.startupPlayerLevitationDelay());
        for (int i = 0; i < expected; i++) {
            int index = i;
            Scheduling.globalLater(plugin, 1L + (long) i * gap,
                    () -> launch(tributes.get(index), layout, world, index, actor));
        }
    }

    /**
     * One tribute: the platform opens, they rise, and a watcher waits for them at the top.
     *
     * <p>Reached through {@link #launchThemAll}'s {@code Scheduling.globalLater} chain, so this runs on the
     * global region thread — correct for {@code world.setTime} back in {@link #launchThemAll}, which is
     * world-wide, but wrong for the two things done here. The platform's middle block belongs to whichever
     * region contains it, and the tribute's potion effect belongs to whichever region currently holds the
     * tribute — the global thread owns neither, so each hops to the scheduler that actually does before
     * touching it.
     */
    private void launch(Player tribute, ArenaLayout layout, World world, int index, UUID actor) {
        ArenaLayout.Stand platform = layout.platforms().get(index);
        Location platformLocation = new Location(world, platform.blockX(), platform.blockY(), platform.blockZ());
        Scheduling.region(plugin, platformLocation, () -> openThePlatform(world, layout, platform));

        Scheduling.entity(plugin, tribute, () -> {
            tribute.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, LEVITATION_TICKS,
                    Math.max(0, settings.startupLevitationAmplifier()), false, false, true));
            effects.play(tribute.getUniqueId(), HungerGamesCues.STARTUP_LAUNCH);

            watchForArrival(tribute, layout, world, index, actor);
        });
    }

    /**
     * Takes the platform's middle block out, remembering what it was, and puts a temporary ceiling above
     * the platform for the flight up to stop against.
     *
     * <p>The middle block is remembered rather than assumed to be stone: platforms are a schematic a server
     * may have replaced with a build of their own, and a sequence that sealed every one of them with stone
     * would leave a stone plug in forty custom platforms. The ceiling needs no such care — it is always a
     * barrier this method placed and {@link #removeArrivalCeiling} always takes away again, never a block
     * that belonged to the arena.
     */
    private void openThePlatform(World world, ArenaLayout layout, ArenaLayout.Stand platform) {
        // One below the standing position — see ArenaLayout.wayUpThrough for the off-by-one this replaced,
        // which stopped every tribute against the block under their own feet.
        Block middle = world.getBlockAt(platform.blockX(), layout.wayUpThrough(platform), platform.blockZ());
        openedPlatforms.put(key(platform), middle.getType());
        middle.setType(Material.AIR, false);

        world.getBlockAt(platform.blockX(), platform.blockY() + CEILING_CLEARANCE_BLOCKS, platform.blockZ())
                .setType(Material.BARRIER, false);
    }

    /** Puts it back exactly as it was. */
    private void sealThePlatform(World world, ArenaLayout layout, ArenaLayout.Stand platform) {
        Material was = openedPlatforms.getOrDefault(key(platform), Material.STONE);
        world.getBlockAt(platform.blockX(), layout.wayUpThrough(platform), platform.blockZ())
                .setType(was, false);
    }

    /** Takes the temporary arrival ceiling back out, once a tribute no longer needs it to fly into. */
    private void removeArrivalCeiling(World world, ArenaLayout.Stand platform) {
        world.getBlockAt(platform.blockX(), platform.blockY() + CEILING_CLEARANCE_BLOCKS, platform.blockZ())
                .setType(Material.AIR, false);
    }

    /**
     * Waits, every tick, for one tribute to reach the top of their tube — flown up under their own
     * levitation the whole way, never teleported.
     *
     * <p>Every tick rather than every few, because the same task also flattens their horizontal velocity —
     * a tribute who bumps a wall on the way up drifts sideways and lands next to their platform instead of on
     * it, and a correction applied five ticks late is a correction applied after they have left the shaft.
     *
     * <h2>Why there is no teleport at the top</h2>
     * There used to be one: the moment {@code arrivesAt} was reached, the tribute was placed exactly onto
     * the platform's own coordinates. Real feedback from watching a launch — a tribute who is visibly
     * flying up their tube and then snaps sideways onto the platform reads as a bug even though it is not
     * one. What actually stops them now is {@link #openThePlatform}'s temporary ceiling: they fly into it,
     * the collision halts them the way any solid block would, and this loop's job becomes purely
     * "notice they have arrived" rather than "put them somewhere". See {@link #CEILING_CLEARANCE_BLOCKS}
     * for why the ceiling itself is never what decides that — it is a physical backstop several blocks
     * above where this loop already recognises them as arrived.
     */
    private void watchForArrival(Player tribute, ArenaLayout layout, World world, int index, UUID actor) {
        ArenaLayout.Stand platform = layout.platforms().get(index);
        // Measured from the platform BLOCK, which is one below the standing position — that is what the
        // source's platformPos was (StartupRunner:355, platformPos.getY() + 1.5). Adding the margin to
        // Stand.y() instead put the threshold a whole block too high, so every tribute had to be carried
        // further before their platform was sealed and their ring went up. See
        // TheSourceIsTheSpecificationTest.
        double arrivesAt = platform.y() - 1 + ARRIVAL_MARGIN;
        UUID uuid = tribute.getUniqueId();
        long[] waited = {0L};

        Scheduling.entityTimer(plugin, tribute, 1L, 1L, task -> {
            if (!tribute.isOnline()) {
                // Counted, not waited for. A tribute who dropped connection halfway up must not cost
                // everybody else the round — see the class note.
                log.warn("{} disconnected during the launch sequence; their platform was sealed.",
                        tribute.getName());
                sealThePlatform(world, layout, platform);
                removeArrivalCeiling(world, platform);
                task.cancel();
                countArrival(layout, world, actor);
                return;
            }

            Vector velocity = tribute.getVelocity();
            tribute.setVelocity(new Vector(0, velocity.getY(), 0));

            if (tribute.getLocation().getY() >= arrivesAt) {
                sealThePlatform(world, layout, platform);
                removeArrivalCeiling(world, platform);
                tribute.removePotionEffect(PotionEffectType.LEVITATION);
                effects.play(uuid, HungerGamesCues.STARTUP_ARRIVE);

                // Immediately, not once everybody is up: the first tribute to arrive would otherwise have
                // the length of the whole sequence to walk off and open a chest.
                BarrierRing.place(world, platform.blockX(), platform.blockY(), platform.blockZ());

                task.cancel();
                countArrival(layout, world, actor);
                return;
            }

            if (++waited[0] >= ARRIVAL_TIMEOUT_TICKS) {
                log.warn("{} never reached their platform within {} seconds — counting them anyway so the "
                        + "sequence can finish.", tribute.getName(), ARRIVAL_TIMEOUT_TICKS / 20);
                tribute.removePotionEffect(PotionEffectType.LEVITATION);
                sealThePlatform(world, layout, platform);
                removeArrivalCeiling(world, platform);
                task.cancel();
                countArrival(layout, world, actor);
            }
        });
    }

    /** One more up. When it is the last one, the round becomes READY — exactly once. */
    private void countArrival(ArenaLayout layout, World world, UUID actor) {
        int now = arrived.incrementAndGet();
        log.debug("{} of {} tribute(s) are on their platforms.", now, expected);
        if (now < expected) {
            return;
        }
        synchronized (this) {
            // Two tributes can arrive on the same tick. Without this claim the READY step runs twice, which
            // demolishes the lobby twice and puts two barrier rings on every platform.
            if (readyClaimed) {
                return;
            }
            readyClaimed = true;
        }
        reachReady(actor, layout, world);
    }

    // ==================== READY ====================

    /** Everybody is up: tidy the arena, place the loot, take the lobby down. */
    private void reachReady(UUID actor, ArenaLayout layout, World world) {
        if (!session.transitionTo(GamePhase.READY)) {
            log.error("Every tribute is in position but the round would not move into READY (it is {}).",
                    session.phase());
            told.refused(actor, "the round would not move into READY from " + session.phase());
            return;
        }

        for (ArenaLayout.Stand ring : undergroundRings) {
            BarrierRing.remove(world, ring.blockX(), ring.blockY(), ring.blockZ());
        }
        undergroundRings.clear();

        for (ArenaLayout.Stand platform : layout.platforms()) {
            clearAbove(world, platform);
            // Placed for everybody, including the tributes whose ring already went up on arrival — placing
            // a ring where one stands is a no-op, because only air becomes barrier.
            BarrierRing.place(world, platform.blockX(), platform.blockY(), platform.blockZ());
        }
        log.info("Barrier rings are up around all {} platform(s).", layout.platformCount());

        // The jump boost stays: it is what keeps tributes from hopping their ring during the countdown.
        // Slowness goes, because the rings do that job now and slowness makes the wait feel like lag.
        for (Player tribute : tributesOnline()) {
            tribute.removePotionEffect(PotionEffectType.SLOWNESS);
            tribute.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,
                    PotionEffect.INFINITE_DURATION, -128, false, false, false));
        }

        // A second later, so the chunks the schematics just wrote have settled and their containers exist
        // as block entities. Filling them on this tick finds empty air where a chest is about to be.
        Scheduling.globalLater(plugin, LOOT_DELAY_TICKS, () -> {
            int filled = loot.fillTheArena(layout);
            log.info("{} container(s) filled with loot.", filled);
        });

        arena.removeTheLobby();

        // Mannequins take whatever platforms real tributes did not — indices [expected, platformCount).
        // Real tributes occupy 0..expected-1 in takeThemUnderground's own order, so this is never a guess
        // at which platforms are free.
        //
        // Bug this fixes: gated on {@code expected == 0} before, so a rehearsal with even one real tribute
        // online (an admin testing alongside spawned mannequins, say) skipped this branch entirely — the
        // mannequins stayed wherever they were spawned while the real tribute alone went through the tube
        // sequence. A pure simulation and a mixed one are the same case: mannequins fill whatever platforms
        // are left over, empty or not.
        List<org.bukkit.Location> leftoverPlatforms = leftoverPlatforms(layout.platforms(), expected).stream()
                .map(stand -> new Location(world, stand.x(), stand.y(), stand.z(), stand.yaw(), 0f))
                .toList();
        if (!leftoverPlatforms.isEmpty()) {
            int placed = simulation.placeOnPlatforms(leftoverPlatforms);
            if (placed > 0) {
                log.info("{} mannequin(s) are standing on platforms for the simulation.", placed);
            }
        }

        told.ready(actor, expected);
    }

    /**
     * The platforms real tributes did not take — {@code takeThemUnderground} always fills indices
     * {@code [0, expected)} in {@code layout.platforms()}'s own order, so whatever comes after {@code expected}
     * is free, whether that is every platform (a pure rehearsal) or none at all (a full round with no
     * mannequins). Its own method, and package-visible, so the bug this fixes — mannequins skipped entirely
     * whenever even one real tribute was online — is pinned by a test that needs no {@code World} or
     * {@code Player} to run.
     */
    static List<ArenaLayout.Stand> leftoverPlatforms(List<ArenaLayout.Stand> platforms, int expected) {
        return platforms.stream().skip(Math.max(0, expected)).toList();
    }

    /**
     * Clears the three-by-three above a platform, and any barrier left over above that.
     *
     * <p>The second half matters on a rebuilt arena: an old round's rings, or the cage from
     * {@code platformbarrier.schem} if a server pasted one by hand, would otherwise still be standing where
     * the new rings are about to go — and a tribute would start the round in a box.
     */
    private void clearAbove(World world, ArenaLayout.Stand platform) {
        int x = platform.blockX();
        int z = platform.blockZ();
        int headroom = platform.blockY() + 1;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = world.getBlockAt(x + dx, headroom, z + dz);
                if (block.getType() != Material.AIR) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        for (int dy = 0; dy <= 5; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block block = world.getBlockAt(x + dx, platform.blockY() + dy, z + dz);
                    if (block.getType() == Material.BARRIER) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    // ==================== who goes where ====================

    /** Everybody online who is in the tournament. */
    private List<Player> tributesOnline() {
        return online.all().stream()
                .filter(player -> session.isWhitelisted(player.getUniqueId()))
                .toList();
    }

    /**
     * Tributes sorted so that team-mates are next to each other on the ring.
     *
     * <p>Not decoration: platforms are handed out in ring order, so this is what decides whether two people
     * on the same team start beside each other or on opposite sides of the cornucopia. Teamless tributes go
     * last, in name order, so a round with no teams at all still lays out the same way every time rather
     * than in whatever order the server happened to list its players.
     */
    private List<Player> byTeam(List<Player> tributes) {
        return tributes.stream()
                .sorted(Comparator
                        .comparing((Player player) -> session.participants().get(player.getUniqueId())
                                .flatMap(participant -> participant.teamId())
                                .map(team -> team.value())
                                .orElse("￿"))
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private World worldOf(ArenaLayout layout) {
        return plugin.getServer().getWorld(layout.world());
    }

    private static String key(ArenaLayout.Stand stand) {
        return stand.blockX() + ":" + stand.blockY() + ":" + stand.blockZ();
    }

    @Override
    public String describe() {
        return "the launch sequence: tubes, lights and levitation";
    }
}
