package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.ArenaStore;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.visual.Schematics;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code /init}: turning a patch of world into an arena.
 *
 * <h2>What actually gets built, in the order it has to happen</h2>
 * The order is not cosmetic — three of the six steps only work because of what ran before them.
 *
 * <ol>
 *   <li><b>The terrain is flattened</b> to a disc of grass at ground level, everything above it cleared, and
 *       the outer five blocks blended back into the natural landscape so the arena does not end in a cliff.</li>
 *   <li><b>The underground room is carved</b> — quartz shell, air interior. <em>Before</em> the tubes, so that
 *       the tubes are pasted into a room that already exists and punch through its ceiling correctly. Carving
 *       it afterwards would fill the bottom of every tube with air and leave them open at the base.</li>
 *   <li><b>The middle is pasted</b> at the centre.</li>
 *   <li><b>The tubes and platforms are pasted</b>, tube at ground level and platform one block up. After the
 *       room, so the tube's own blocks override the air that was just carved.</li>
 *   <li><b>The lobby is built</b> above the arena, and the world's spawn is put on its roof.</li>
 *   <li><b>The world is configured</b>: border centre and size, the Nether's border with it, and the
 *       preflight difficulty so nobody is killed by a zombie while choosing a team.</li>
 * </ol>
 *
 * <h2>Why this runs in one go, and blocks</h2>
 * A large arena is several hundred thousand block writes and it will visibly hang the server for a few
 * seconds. That is deliberate and it is the source's own behaviour kept: {@code /init} is run by an admin
 * before an evening starts, with nobody else in the world, and the alternative — spreading the work over
 * many ticks — means a window in which somebody can walk into a half-built arena, fall through a floor that
 * has not been laid yet, or stand where a schematic is about to be pasted. A tournament is not damaged by
 * five seconds of nothing happening; it is damaged by an arena that is subtly incomplete.
 *
 * <p>What is <em>not</em> kept from the source is doing it on whatever thread happened to call. Everything
 * here goes through {@link Scheduling#region}, so it runs on the thread that owns those blocks on Folia and
 * on the main thread everywhere else.
 *
 * <h2>Why the whole layout is computed first and stored</h2>
 * {@link ArenaLayout} is worked out before the first block is placed and written to {@link ArenaStore} at the
 * end. The source kept the same numbers in a static singleton, so a restart between {@code /init} and
 * {@code /start} — an entirely ordinary thing, since the arena is built the afternoon before — came back
 * with a standing arena and no idea where any of it was.
 */
public final class ArenaBuildService implements IHungerGamesService {

    /** The schematic pasted at the centre. */
    public static final String MIDDLE = "middle.schem";

    /** The tube a tribute rises through, pasted at ground level and extending downwards. */
    public static final String TUBE = "tube.schem";

    /** The platform a tribute stands on, pasted one block above ground level. */
    public static final String PLATFORM = "platform.schem";

    /** How far above the flattened ground anything left standing is cleared away. */
    private static final int CLEAR_HEIGHT = 50;

    /** How deep the dirt under the grass goes. Three layers, so a tribute digging down hits stone quickly. */
    private static final int SOIL_DEPTH = 3;

    /** Where a world lives, by name — so nothing here has to reach for the server statically. */
    @FunctionalInterface
    public interface Worlds {
        Optional<World> byName(String name);
    }

    /**
     * How whoever ran {@code /init} finds out how it went.
     *
     * <p>Needed because the build does not happen while the command is running. {@link Scheduling#region}
     * runs its task on the next tick of the thread that owns those blocks — always, on Folia and off it —
     * so the command has returned long before the first block is placed. Reporting success from the command
     * would be reporting that the job was accepted, which is not the same sentence and is exactly the one
     * somebody stops reading the console after.
     */
    public interface Told {

        void building(UUID who, int platforms);

        void completed(UUID who, ArenaLayout layout);

        void failed(UUID who, String why);
    }

    private final Plugin plugin;
    private final GameSession session;
    private final Schematics schematics;
    private final ArenaStore arenaStore;
    private final Worlds worlds;
    private final Function<UUID, Player> onlinePlayer;
    private final Told told;
    private final LogChannel log;

    private volatile HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private volatile ArenaLayout built;

    public ArenaBuildService(Plugin plugin, GameSession session, Schematics schematics,
                             ArenaStore arenaStore, Worlds worlds,
                             Function<UUID, Player> onlinePlayer, Told told, LogChannel log) {
        this.plugin = plugin;
        this.session = session;
        this.schematics = schematics;
        this.arenaStore = arenaStore;
        this.worlds = worlds;
        this.onlinePlayer = onlinePlayer;
        this.told = told;
        this.log = log;
        this.built = arenaStore.load().orElse(null);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** The arena as it stands, or empty when {@code /init} has not been run — or its file was lost. */
    public Optional<ArenaLayout> layout() {
        return Optional.ofNullable(built);
    }

    /** One line for the preflight screen, in the shape {@link PreflightCheckService.ArenaStatus} wants. */
    public Optional<String> centreDescription() {
        return layout().map(ArenaLayout::describe);
    }

    /** The middle of the arena as a real location, for anything that needs to point at it. */
    public Optional<Location> centre() {
        return layout().flatMap(layout -> worlds.byName(layout.world())
                .map(world -> new Location(world, layout.centreX() + 0.5, layout.centreY(),
                        layout.centreZ() + 0.5)));
    }

    /**
     * The {@code /init} stage, in the shape {@link GameControlService.Stage} takes.
     *
     * <p>The actor has to be a player, and that is not an oversight: the arena is built <em>where they are
     * standing</em>. A console has no position, so a console running {@code /init} is asking for an arena
     * somewhere this class would have to invent — and the version that invented one built it at spawn, on
     * top of whatever was there.
     */
    public GameControlService.BuildStage initStage() {
        // The count comes straight through now. It used to be re-derived from the tribute register here,
        // because Stage carried only the actor — and on a server whose register was empty that turned a
        // gamemaster's choice of 42 into two platforms. See GameControlService.BuildStage.
        return this::build;
    }

    /**
     * Starts building the arena for this many tributes.
     *
     * @return whether the job was accepted — <em>not</em> whether the arena stands. The blocks are placed on
     *         the next tick of the thread that owns them; {@link Told} is how the outcome gets back
     */
    public boolean build(UUID actor, int playerCount) {
        Player who = actor == null ? null : onlinePlayer.apply(actor);
        if (who == null) {
            log.error("/init needs somebody standing where the arena should go — the arena is built around "
                    + "whoever runs it, and a console has no position.");
            return false;
        }
        Location centre = who.getLocation();
        World world = centre.getWorld();
        if (world == null) {
            log.error("/init was run by somebody who is not in a world.");
            return false;
        }

        if (!claimPreflight()) {
            return false;
        }

        // From the schematic itself, and only from the setting when that cannot be read. A guessed tube
        // height puts the underground room's ceiling in the wrong place, and the symptom is a tribute
        // levitating into stone instead of up a tube.
        OptionalInt measured = schematics.height(TUBE);
        int tubeHeight = measured.orElseGet(() -> {
            log.warn("The tube schematic's height could not be read; falling back to arena.tube-depth ({}).",
                    settings.tubeDepth());
            return settings.tubeDepth();
        });

        ArenaLayout layout = ArenaLayout.of(world.getName(), centre.getBlockX(), centre.getBlockY(),
                centre.getBlockZ(), playerCount, tubeHeight, settings);

        log.info("Building an arena for {} tribute(s) at {} — the server will be busy for a few seconds.",
                playerCount, layout.describe());
        told.building(actor, layout.platformCount());

        // One region task for the whole build. See the class note on why this is not spread over ticks.
        Scheduling.region(plugin, centre, () -> {
            if (place(world, layout, who)) {
                told.completed(actor, layout);
            } else {
                told.failed(actor, "the arena could not be finished — see the console");
            }
        });
        return true;
    }

    /** Everything that touches a block. Called on the thread that owns them. */
    private boolean place(World world, ArenaLayout layout, Player who) {
        try {
            flattenTheGround(world, layout);
            carveTheUndergroundRoom(world, layout);

            if (!schematics.paste(MIDDLE, at(world, layout.centreX(), layout.centreY(), layout.centreZ()))) {
                log.error("The middle could not be pasted, so the arena was left unfinished.");
                return giveUp();
            }
            if (!placePlatformsAndTubes(world, layout)) {
                return giveUp();
            }
            buildTheLobby(world, layout);
            configureTheWorld(world, layout);

            built = layout;
            if (!arenaStore.save(layout)) {
                // Not fatal: the arena is standing and this round can be run. It is the *next* restart that
                // will have forgotten where it is, which is worth saying out loud now rather than then.
                log.warn("The arena was built but could not be written to disk — a restart before the round "
                        + "starts will lose track of where it is.");
            }

            // Inside the lobby, not on its roof.
            //
            // The world's spawn is the roof, deliberately — that is where somebody who is not a tribute lands,
            // so they can see what is happening without being in the waiting room. Whoever ran /init is a
            // different case: they are standing in the middle of an arena that has just been pasted around
            // them and they want to be where the tributes will be. Putting them on the roof left them on top
            // of a glass box wondering how to get in, which is what happened the first time this ran.
            ArenaLayout.Stand inside = layout.lobbyCentre();
            who.teleport(new Location(world, inside.x(), inside.y(), inside.z(), inside.yaw(), 0f));
            who.setGameMode(GameMode.ADVENTURE);

            if (!session.transitionTo(GamePhase.LOBBY)) {
                log.error("The arena was built but the round would not move into LOBBY (it is {}).",
                        session.phase());
                return giveUp();
            }
            log.info("Arena complete: {} platforms, lobby at Y={}, underground room Y={}–{}.",
                    layout.platformCount(), layout.lobbyBaseY(), layout.roomFloorY(), layout.roomCeilingY());
            return true;
        } catch (RuntimeException failed) {
            // Caught broadly because the arena is half-built either way, and an escaping exception here
            // leaves the round stuck in PREFLIGHT with a stack trace naming a block setter rather than the
            // step it was on.
            log.error("The arena could not be built: {}", failed.toString());
            return giveUp();
        }
    }

    /**
     * Puts the round back where {@code /init} can be run again.
     *
     * <p>The alternative is a round stuck in {@code PREFLIGHT} forever: nothing transitions out of it except
     * a completed build, so a failure halfway leaves {@code canInit()} false and the only way out is
     * restarting the server. Tributes and teams are kept — they were entered by hand and have nothing to do
     * with why a schematic would not paste.
     */
    private boolean giveUp() {
        session.resetForNextRound();
        return false;
    }

    /**
     * Moves the round into {@link GamePhase#PREFLIGHT}, from wherever it actually is.
     *
     * <h2>Why a finished round is cleared first</h2>
     * A finished round is exactly when the next arena gets built — it is what a gamemaster does between two
     * rounds of an evening. The phase machine has no {@code FINISHED -> PREFLIGHT} edge on purpose: the way
     * back is through {@link GamePhase#NOT_INITIALIZED}, and that is what clears the winner, the elimination
     * states and the clock. So the clearing happens here rather than by widening the machine, and the
     * tributes and teams survive it — they were entered by hand and the next round is the same evening.
     *
     * <h2>The bug this is</h2>
     * Found on a live server. {@code /init} answered "the arena could not be built — see the console" after
     * every completed round, and the console said the round could not leave {@code FINISHED}.
     * {@code GameControlService.canInit()} lists {@code FINISHED} as a phase {@code /init} may run from; the
     * machine refused the move. Both were individually tested and the two had never been asked the same
     * question in one test — which is what {@code TheNextRoundCanBeBuiltTest} now does.
     *
     * @return whether the round is now in preflight
     */
    public boolean claimPreflight() {
        if (session.phase() == GamePhase.FINISHED) {
            session.resetForNextRound();
        }
        if (!session.transitionTo(GamePhase.PREFLIGHT)) {
            log.error("The round could not move into PREFLIGHT from {} — nothing was built.",
                    session.phase());
            return false;
        }
        return true;
    }

    // ==================== the steps ====================

    /**
     * Flattens the ground to a disc of grass, clears what is above it, and blends the edge.
     *
     * <p>The blend is the difference between an arena and a crater. Inside {@code terrainRadius} everything
     * is level; from there to {@link ArenaLayout#BLEND_DISTANCE} further out the target height slides from
     * the arena's floor to whatever the land was already doing.
     */
    private void flattenTheGround(World world, ArenaLayout layout) {
        int reach = layout.blendedRadius();
        int groundY = layout.groundY() - 1;
        int columns = 0;

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (distance > reach) {
                    continue;
                }
                int x = layout.centreX() + dx;
                int z = layout.centreZ() + dz;

                int targetY;
                if (distance <= layout.terrainRadius()) {
                    targetY = groundY;
                } else {
                    double blend = (distance - layout.terrainRadius()) / ArenaLayout.BLEND_DISTANCE;
                    int natural = world.getHighestBlockYAt(x, z);
                    targetY = (int) Math.round(groundY + (natural - groundY) * blend);
                }

                world.getBlockAt(x, targetY, z).setType(Material.GRASS_BLOCK, false);
                for (int depth = 1; depth <= SOIL_DEPTH; depth++) {
                    world.getBlockAt(x, targetY - depth, z).setType(Material.DIRT, false);
                }
                for (int y = targetY + 1; y <= targetY + CLEAR_HEIGHT; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();
                    // Barriers are left alone: a previous round's rings may still be standing here, and
                    // they are cleared by the sequence that owns them rather than by a terrain pass.
                    if (material != Material.AIR && material != Material.BARRIER) {
                        block.setType(Material.AIR, false);
                    }
                }
                columns++;
            }
        }
        log.info("Terrain prepared: {} columns to a radius of {} ({} blended).",
                columns, layout.terrainRadius(), ArenaLayout.BLEND_DISTANCE);
    }

    /**
     * Carves the circular quartz room the launch tubes come down into.
     *
     * <p>The interior is set to air rather than merely left alone: this is dug out of solid stone, and the
     * tubes pasted afterwards need somewhere to end. The ceiling carries redstone lamps in a scattered
     * pattern, unlit — {@code StartupSequenceService} lights them ring by ring as the sequence begins, which
     * is the one moment anybody is down there to see it.
     */
    private void carveTheUndergroundRoom(World world, ArenaLayout layout) {
        int radius = layout.roomRadius();
        int lamps = 0;

        for (int y = layout.roomFloorY(); y <= layout.roomCeilingY(); y++) {
            for (int dx = -radius - 1; dx <= radius + 1; dx++) {
                for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                    double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                    int x = layout.centreX() + dx;
                    int z = layout.centreZ() + dz;

                    if (y == layout.roomFloorY()) {
                        if (distance <= radius) {
                            world.getBlockAt(x, y, z).setType(Material.QUARTZ_BLOCK, false);
                        }
                    } else if (y == layout.roomCeilingY()) {
                        if (distance <= radius) {
                            // Every third block, and never right over the middle, where the schematic's own
                            // structure comes down.
                            if ((dx + dz) % 3 == 0 && distance > 2) {
                                world.getBlockAt(x, y, z).setType(Material.REDSTONE_LAMP, false);
                                lamps++;
                            } else {
                                world.getBlockAt(x, y, z).setType(Material.QUARTZ_BLOCK, false);
                            }
                        }
                    } else if (distance >= radius && distance < radius + 1) {
                        world.getBlockAt(x, y, z).setType(Material.QUARTZ_BLOCK, false);
                    } else if (distance < radius) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
        log.info("Underground room carved: Y={}–{}, radius {}, {} lamps in the ceiling.",
                layout.roomFloorY(), layout.roomCeilingY(), radius, lamps);
    }

    /** Tube at ground level, platform one block above it, once per tribute. */
    private boolean placePlatformsAndTubes(World world, ArenaLayout layout) {
        for (ArenaLayout.Stand stand : layout.platforms()) {
            Location tube = at(world, stand.blockX(), layout.groundY(), stand.blockZ());
            if (!schematics.paste(TUBE, tube)) {
                log.error("A tube could not be pasted at X:{} Z:{} — the arena was left unfinished.",
                        stand.blockX(), stand.blockZ());
                return false;
            }
            // Deliberately not platformbarrier.schem. Tributes are held in place by the barrier rings the
            // start-up sequence places and the countdown removes; a pasted cage leaves blocks standing over
            // the platforms that nothing then takes down.
            if (!schematics.paste(PLATFORM, at(world, stand.blockX(), layout.groundY() + 1,
                    stand.blockZ()))) {
                log.error("A platform could not be pasted at X:{} Z:{} — the arena was left unfinished.",
                        stand.blockX(), stand.blockZ());
                return false;
            }
        }
        log.info("{} platform(s) and tube(s) placed.", layout.platformCount());
        return true;
    }

    /**
     * Builds the glass box tributes wait in, and clears its interior.
     *
     * <p>Floor, four walls, ceiling, then the inside emptied — in that order, because the box is built at a
     * height where there may already be terrain, and a box with a hillside inside it is a box some tributes
     * are standing in the middle of.
     */
    private void buildTheLobby(World world, ArenaLayout layout) {
        Material material = settings.lobbyBlockType();
        int baseX = layout.lobbyBaseX();
        int baseY = layout.lobbyBaseY();
        int baseZ = layout.lobbyBaseZ();
        int width = layout.lobbyWidth();
        int depth = layout.lobbyDepth();
        int height = layout.lobbyHeight();

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                world.getBlockAt(baseX + x, baseY, baseZ + z).setType(material, false);
                world.getBlockAt(baseX + x, baseY + height + 1, baseZ + z).setType(material, false);
            }
        }
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                world.getBlockAt(baseX + x, baseY + y, baseZ).setType(material, false);
                world.getBlockAt(baseX + x, baseY + y, baseZ + depth - 1).setType(material, false);
            }
            for (int z = 0; z < depth; z++) {
                world.getBlockAt(baseX, baseY + y, baseZ + z).setType(material, false);
                world.getBlockAt(baseX + width - 1, baseY + y, baseZ + z).setType(material, false);
            }
        }
        for (int x = 1; x < width - 1; x++) {
            for (int z = 1; z < depth - 1; z++) {
                for (int y = 1; y <= height; y++) {
                    world.getBlockAt(baseX + x, baseY + y, baseZ + z).setType(Material.AIR, false);
                }
            }
        }

        // On the roof, not inside. Somebody who is not a tribute lands on top and can watch; tributes are
        // put inside by the lobby listener, which is the one thing that knows who belongs in there.
        ArenaLayout.Stand roof = layout.lobbyRoofSpawn();
        world.setSpawnLocation(new Location(world, roof.x(), roof.y(), roof.z()));
        log.info("Lobby built at Y={} ({}×{}×{}).", baseY, width, depth, height);
    }

    /** Border centre and size, the Nether's border with it, and the difficulty for the wait. */
    private void configureTheWorld(World world, ArenaLayout layout) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(layout.centreX() + 0.5, layout.centreZ() + 0.5);
        border.setSize(settings.borderInitialSize());

        if (settings.borderScaleNether()) {
            // A round takes place in one world, but a tribute who steps through a portal must not walk out
            // from under the border. The Nether's coordinates are an eighth of the Overworld's, so its
            // centre is too — and its size is the Overworld's eighth for the same reason.
            worlds.byName(world.getName() + "_nether").ifPresentOrElse(nether -> {
                WorldBorder netherBorder = nether.getWorldBorder();
                netherBorder.setCenter((layout.centreX() + 0.5) / 8.0, (layout.centreZ() + 0.5) / 8.0);
                netherBorder.setSize(settings.borderInitialSize() / 8.0);
                log.info("The Nether's border was centred and set to {} blocks.",
                        settings.borderInitialSize() / 8.0);
            }, () -> log.info("There is no Nether beside {}, so no Nether border was set.",
                    world.getName()));
        }

        world.setDifficulty(settings.preflightDifficulty());
        log.info("Border set to {} blocks and difficulty to {} for the wait.",
                settings.borderInitialSize(), settings.preflightDifficulty());
    }

    // ==================== the border, once an arena exists ====================

    /**
     * The world border of the arena that is actually standing.
     *
     * <p>What {@code BorderService} moves. Handed the arena rather than a world, so that a phase firing
     * before {@code /init} does nothing instead of resizing whatever world the server happens to call
     * default — which is what a helpful fallback would have done, on a survival world, mid-evening.
     */
    public BorderService.WorldBorderTarget borderTarget() {
        return new BorderService.WorldBorderTarget() {

            @Override
            public double currentSize() {
                return arenaWorld().map(world -> world.getWorldBorder().getSize())
                        .orElseGet(() -> (double) settings.borderInitialSize());
            }

            @Override
            public void shrinkOverworld(double targetSize, long ticks) {
                arenaWorld().ifPresentOrElse(
                        world -> world.getWorldBorder().setSize(targetSize, ticksToSeconds(ticks)),
                        () -> log.warn("The border was asked to close to {} blocks, but no arena is built.",
                                targetSize));
            }

            @Override
            public void shrinkNether(double targetSize, long ticks) {
                if (!settings.borderScaleNether()) {
                    return;
                }
                layout().flatMap(arena -> worlds.byName(arena.world() + "_nether"))
                        .ifPresent(nether -> nether.getWorldBorder()
                                .setSize(targetSize / 8.0, ticksToSeconds(ticks)));
            }

            @Override
            public void resetTo(double size) {
                arenaWorld().ifPresent(world -> world.getWorldBorder().setSize(size));
                if (settings.borderScaleNether()) {
                    layout().flatMap(arena -> worlds.byName(arena.world() + "_nether"))
                            .ifPresent(nether -> nether.getWorldBorder().setSize(size / 8.0));
                }
            }

            /** Bukkit counts a border move in seconds; everything else in this module counts in ticks. */
            private long ticksToSeconds(long ticks) {
                return Math.max(0L, ticks / 20L);
            }
        };
    }

    private Optional<World> arenaWorld() {
        return layout().flatMap(arena -> worlds.byName(arena.world()));
    }

    // ==================== small shared helpers ====================

    /**
     * Lights the ceiling's redstone lamps in one ring.
     *
     * <p>Here rather than in the start-up sequence because this class is what knows the ceiling is a ceiling
     * and where it is — and because the same pass is what a rebuilt arena needs to put the lamps out again.
     */
    public int lightTheCeiling(int ringIndex, boolean lit) {
        Optional<ArenaLayout> arena = layout();
        Optional<World> world = arenaWorld();
        if (arena.isEmpty() || world.isEmpty()) {
            return 0;
        }
        ArenaLayout layout = arena.get();
        int changed = 0;
        int radius = layout.roomRadius();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
                if (distance != ringIndex || distance > radius) {
                    continue;
                }
                Block block = world.get().getBlockAt(layout.centreX() + dx, layout.roomCeilingY(),
                        layout.centreZ() + dz);
                if (block.getType() == Material.REDSTONE_LAMP
                        && block.getBlockData() instanceof Lightable lightable) {
                    lightable.setLit(lit);
                    block.setBlockData(lightable, false);
                    changed++;
                }
            }
        }
        return changed;
    }

    /** How many lamp rings there are, so the sequence knows how long its light show runs. */
    public int ceilingRings() {
        return layout().map(ArenaLayout::roomRadius).orElse(0);
    }

    /**
     * Takes the glass box down again.
     *
     * <p>Called when everybody is on a platform. A lobby left standing is a glass roof over the arena that
     * blocks the sky, and a place an eliminated tribute in spectator mode drifts into and cannot see out of.
     */
    public void removeTheLobby() {
        Optional<ArenaLayout> arena = layout();
        Optional<World> world = arenaWorld();
        if (arena.isEmpty() || world.isEmpty()) {
            return;
        }
        ArenaLayout layout = arena.get();
        for (int x = 0; x < layout.lobbyWidth(); x++) {
            for (int z = 0; z < layout.lobbyDepth(); z++) {
                for (int y = 0; y <= layout.lobbyHeight() + 2; y++) {
                    world.get().getBlockAt(layout.lobbyBaseX() + x, layout.lobbyBaseY() + y,
                            layout.lobbyBaseZ() + z).setType(Material.AIR, false);
                }
            }
        }
        log.info("The lobby was removed.");
    }

    private static Location at(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    @Override
    public String describe() {
        return "building the arena a round is fought in";
    }
}
