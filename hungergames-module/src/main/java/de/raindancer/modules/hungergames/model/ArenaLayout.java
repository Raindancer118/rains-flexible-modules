package de.raindancer.modules.hungergames.model;

import de.raindancer.modules.hungergames.HungerGamesSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Every coordinate an arena is made of, worked out before a single block is placed.
 *
 * <h2>Why this exists as a record rather than as the builder's local variables</h2>
 * In the plugin this is ported from, all of this was interleaved with the block placement that used it: the
 * underground room's floor was computed halfway down a triple-nested loop that was already setting quartz,
 * the lobby's base corner was recomputed independently in three different classes, and the tube's depth was
 * read from a schematic in one place and from a config key in another. Nothing could be checked without
 * running {@code /init} on a server and walking around the result.
 *
 * <p>The specific cost of that was not theoretical. The lobby's base corner is
 * {@code centre - width/2}, and it was written out by hand in {@code PreflightRunner} (to build the box), in
 * {@code StartupRunner} (to demolish it) and in the lobby containment check (to decide who is inside).
 * Three copies of one subtraction, and the containment check is the one that has to agree with the other two
 * or tributes are teleported into a box they are then judged to be outside of.
 *
 * <h2>The vertical stack, which is the part worth writing down</h2>
 * Reading upwards from the bottom:
 *
 * <pre>
 *   roomFloorY        quartz floor — tributes stand on floorY + 1, at the foot of their tube
 *   …                 the room's interior, {@code arena.underground-room-height} blocks of it
 *   roomCeilingY      quartz and redstone lamps
 *   …                 the tube's lowest {@link #TUBE_PROTRUSION} blocks hang through the ceiling
 *   tubeBottomY       where the tube schematic ends
 *   …                 the tube itself, as many blocks as the schematic is tall
 *   centreY           ground level: the tube's top, and where the platform schematic is pasted onto
 *   centreY + 1       the platform's own surface — a tribute's feet, and where the barrier ring goes
 * </pre>
 *
 * <p>{@link #TUBE_PROTRUSION} is the one number here that is neither configurable nor derived, and it is
 * load-bearing: if the tube ended flush with the ceiling, the ceiling block would seal it and the tribute
 * underneath would levitate into quartz. Three blocks of overhang is what makes the tube visibly a tube from
 * inside the room.
 *
 * <h2>What this deliberately does not know</h2>
 * Nothing here touches a {@code World}, a {@code Location} or a {@code Block}. It takes a world's
 * <em>name</em> so that a caller can tell two arenas apart and so that the geometry can be written to disk
 * and read back, and it hands out plain numbers. {@code ArenaBuildService} is what turns them into blocks.
 */
public record ArenaLayout(

        String world,
        int centreX,
        int centreY,
        int centreZ,

        /** The ring the platforms sit on — see {@link ArenaRing} for why its radius comes from the chord. */
        ArenaRing ring,

        /** Where each tribute ends up standing, in ring order, facing the middle. */
        List<Stand> platforms,

        /** Where each tribute starts, at the foot of their own tube, in the same order. */
        List<Stand> undergroundStarts,

        /** How far out the ground is flattened, before the blend ring is added on top. */
        int terrainRadius,

        int roomFloorY,
        int roomCeilingY,
        int roomRadius,

        int lobbyBaseX,
        int lobbyBaseY,
        int lobbyBaseZ,
        int lobbyWidth,
        int lobbyDepth,
        int lobbyHeight) {

    /**
     * How far the bottom of a tube hangs through the underground room's ceiling, in blocks.
     *
     * <p>Not configurable, on purpose. At zero the ceiling seals the tube and the levitation sequence lifts
     * a tribute into a quartz block; at one it is a hole rather than a tube. Three is what makes it read as
     * a shaft coming down into the room, which is the whole visual of the launch sequence.
     */
    public static final int TUBE_PROTRUSION = 3;

    /**
     * How far past {@link #terrainRadius} the ground is smoothed back into natural terrain, in blocks.
     *
     * <p>Without it the flattened circle ends in a cliff, which is what the arena looked like in the version
     * this is ported from before somebody added the blend.
     */
    public static final int BLEND_DISTANCE = 5;

    /** A position somebody stands at, and which way they look. */
    public record Stand(double x, double y, double z, float yaw) {

        /** The block this position is in. */
        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }
    }

    public ArenaLayout {
        platforms = List.copyOf(platforms);
        undergroundStarts = List.copyOf(undergroundStarts);
    }

    /**
     * The whole layout for a round.
     *
     * @param tubeHeight how tall the tube schematic actually is. The caller reads it from the schematic
     *                   itself and falls back to {@code arena.tube-depth} only when it cannot — a guessed
     *                   height puts the underground room's ceiling in the wrong place, and the symptom is
     *                   tributes levitating into stone rather than up a tube
     */
    public static ArenaLayout of(String world, int centreX, int centreY, int centreZ,
                                 int playerCount, int tubeHeight, HungerGamesSettings settings) {
        ArenaRing ring = ArenaRing.forTributes(playerCount, settings.platformWidth(),
                settings.platformMinGap());

        // The vertical stack, bottom up. See the class note for the diagram.
        int tubeBottomY = centreY - Math.max(1, tubeHeight);
        int ceilingY = tubeBottomY + TUBE_PROTRUSION;
        int floorY = ceilingY - Math.max(1, settings.undergroundRoomHeight()) - 1;

        int extra = Math.max(0, settings.undergroundRoomExtraRadius());
        int roomRadius = (int) Math.ceil(ring.radius()) + extra;

        // Wider than the room, because the radius locates a platform's *centre* and half of every platform
        // sticks out past it. Flattening only to the radius is what left each platform with a wall on its
        // outer edge in the version this is ported from — see ArenaRing.groundRadius.
        int terrainRadius = ring.groundRadius(extra);

        List<Stand> platforms = new ArrayList<>();
        List<Stand> underground = new ArrayList<>();
        for (ArenaRing.Spot spot : ring.spots()) {
            int blockX = (int) Math.floor(centreX + spot.x());
            int blockZ = (int) Math.floor(centreZ + spot.z());

            // Centred in the block on both axes. A tribute standing on a block boundary clips the glass of
            // their own tube on the way up, which reads as the levitation snagging.
            platforms.add(new Stand(blockX + 0.5, centreY + 1.0, blockZ + 0.5, spot.yaw()));
            underground.add(new Stand(blockX + 0.5, floorY + 1.0, blockZ + 0.5, spot.yaw()));
        }

        int lobbyWidth = Math.max(3, settings.lobbyWidth());
        int lobbyDepth = Math.max(3, settings.lobbyDepth());
        int lobbyHeight = Math.max(2, settings.lobbyHeight());

        return new ArenaLayout(world, centreX, centreY, centreZ, ring, platforms, underground,
                terrainRadius, floorY, ceilingY, roomRadius,
                centreX - lobbyWidth / 2, centreY + settings.lobbyHeightOffset(),
                centreZ - lobbyDepth / 2, lobbyWidth, lobbyDepth, lobbyHeight);
    }

    /** How many tributes this arena was built for. */
    public int platformCount() {
        return platforms.size();
    }

    /** The outermost ring the terrain work touches, blend included. */
    public int blendedRadius() {
        return terrainRadius + BLEND_DISTANCE;
    }

    /** Ground level: the top of the tubes, and what the platform schematic is pasted onto. */
    public int groundY() {
        return centreY;
    }

    /** Where a tribute stands once they are on a platform — one above ground level. */
    public int standingY() {
        return centreY + 1;
    }

    /**
     * The middle of the lobby's interior, where a tribute waiting for a round is put.
     *
     * <p>Half a block above the floor rather than on it, which is where a player teleported to an integer Y
     * ends up anyway — spelled out so that the containment check and the teleport agree exactly.
     */
    public Stand lobbyCentre() {
        return new Stand(lobbyBaseX + lobbyWidth / 2.0, lobbyBaseY + 1.5,
                lobbyBaseZ + lobbyDepth / 2.0, 0f);
    }

    /**
     * Where the world's spawn point goes: on the <em>roof</em> of the glass box, not inside it.
     *
     * <p>Deliberate, and it is the source's own decision kept. Somebody who is not a tribute — a spectator, a
     * staff member, somebody who wandered in — lands on top and can see what is happening. Tributes are moved
     * inside by the lobby listener, which is the one thing that knows who belongs in there.
     */
    public Stand lobbyRoofSpawn() {
        return new Stand(lobbyBaseX + lobbyWidth / 2.0, lobbyBaseY + lobbyHeight + 2.0,
                lobbyBaseZ + lobbyDepth / 2.0, 0f);
    }

    /** The lobby's geometry in the shape {@code LobbyBoxService} asks about containment in. */
    public boolean isInsideTheLobby(double x, double y, double z) {
        return x >= lobbyBaseX && x < lobbyBaseX + lobbyWidth
                && z >= lobbyBaseZ && z < lobbyBaseZ + lobbyDepth
                && y >= lobbyBaseY && y <= lobbyBaseY + lobbyHeight + 1;
    }

    /**
     * Whether a horizontal offset from the middle falls inside the underground room.
     *
     * <p>The room is a circle, and the wall is the one-block band at its edge — the same test the builder
     * uses to decide whether a block is floor, wall or air, so the two cannot disagree.
     */
    public boolean isInsideTheRoom(int dx, int dz) {
        return Math.sqrt((double) dx * dx + (double) dz * dz) < roomRadius;
    }

    /** How many blocks tall the underground room's interior is. */
    public int roomInteriorHeight() {
        return roomCeilingY - roomFloorY - 1;
    }

    /** One line for a log or a preflight row. */
    public String describe() {
        return world + " X:" + centreX + " Y:" + centreY + " Z:" + centreZ
                + " (" + platformCount() + " platforms, r=" + Math.round(ring.radius()) + ")";
    }
}
