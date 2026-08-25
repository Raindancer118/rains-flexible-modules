package de.raindancer.modules.wallsroads;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about walls and roads. The record <em>is</em> the schema — see
 * {@code MannequinSettings} for why every component has a {@code with…} rather than a positional
 * constructor being the way to change one.
 */
@Settings(id = "wallsroads", topics = {
        @Topic(path = "wallsroads", title = "Walls and Roads", icon = Material.STONE_BRICK_WALL),
        @Topic(path = "wallsroads/access", title = "Who may build", icon = Material.NAME_TAG),
        @Topic(path = "wallsroads/wall", title = "Wall defaults", icon = Material.COBBLESTONE_WALL),
        @Topic(path = "wallsroads/road", title = "Road defaults", icon = Material.GRAVEL),
        @Topic(path = "wallsroads/gate", title = "Gates", icon = Material.OAK_FENCE_GATE),
        @Topic(path = "wallsroads/build", title = "Building", icon = Material.PISTON),
        @Topic(path = "wallsroads/route", title = "Routing", icon = Material.COMPASS),
})
public record WallsRoadsSettings(

        @In("wallsroads/access") @Title("Anybody may mark out a wall or road")
        @Describe("On lets any player use /wallsroads wall new or /wallsroads road new. Off gates "
                + "it behind the create permission, for a server that wants only builders placing them.")
        // Not "open-creation": mannequin-module already declares a setting by that name, and Core
        // warns at boot that a command using the bare name reaches whichever plugin it finds first.
        // Renamed rather than left colliding because this module has never shipped — nobody has a
        // config with the old key in it, and a colliding name is only ever a problem later.
        @Key("open-marking")
        boolean openCreation,

        @In("wallsroads/wall") @Title("Default wall material")
        @Describe("What a freshly marked wall builds from until its owner picks something else.")
        @Key("default-wall-material")
        Material defaultWallMaterial,

        @In("wallsroads/wall") @Title("Default wall height") @Range(min = 1, max = 320)
        @Describe("Blocks, measured up from the marked base.")
        @Key("default-wall-height")
        int defaultWallHeight,

        @In("wallsroads/wall") @Title("Default wall thickness") @Range(min = 1, max = 9)
        @Describe("Blocks, measured across the marked line. One is a fence; a castle wall is three or "
                + "more, and the wall-walk on top is as wide as this.")
        @Key("default-wall-thickness")
        int defaultWallThickness,

        @In("wallsroads/wall") @Title("Default corner radius") @Range(min = 0, max = 32)
        @Describe("0 keeps sharp corners. Anything higher rounds every corner to that many blocks, "
                + "unless the owner picks a shape per wall.")
        @Key("default-corner-radius")
        int defaultCornerRadius,

        @In("wallsroads/road") @Title("Default road material")
        @Describe("What a freshly marked road paves with until its owner picks something else.")
        @Key("default-road-material")
        Material defaultRoadMaterial,

        @In("wallsroads/road") @Title("Default road width") @Range(min = 1, max = 32)
        @Describe("Blocks, centred on the marked path.")
        @Key("default-road-width")
        int defaultRoadWidth,

        @In("wallsroads/gate") @Title("Default gate height") @Range(min = 1, max = 16)
        @Describe("How tall the opening is where a road cuts through a wall.")
        @Key("default-gate-height")
        int defaultGateHeight,

        @In("wallsroads/gate") @Title("Signs are placed automatically")
        @Describe("A road gets a name-board at each end and at every gate it passes through, unless "
                + "switched off here.")
        @Key("auto-place-signs")
        boolean autoPlaceSigns,

        @In("wallsroads/build") @Title("Blocks placed per tick") @Range(min = 1, max = 10000)
        @Describe("How many blocks a single build/teardown batch places before yielding — the pacing "
                + "that keeps a huge wall from freezing the server for one tick.")
        @Key("blocks-per-batch")
        int blocksPerBatch,

        @In("wallsroads/build") @Title("Maximum vertices") @Range(min = 3, max = 500)
        @Describe("The most corners a single wall or road may be marked with.")
        @Key("max-vertices")
        int maxVertices,

        @In("wallsroads/road") @Title("How much a road curves") @Range(min = 0, max = 5)
        @Describe("How many rounds of corner-cutting a freshly marked road gets. 0 keeps the hard "
                + "angles between the points that were clicked; higher makes it sweep. Roads people "
                + "actually build curve, and a route made of straight segments meeting at corners "
                + "never looks like one.")
        @Key("road-curviness")
        int roadCurviness,

        @In("wallsroads/road") @Title("Roads are charged for")
        @Describe("On means building a road or wall takes the blocks out of the builder's inventory, "
                + "and stops when they run out. Off builds it from nothing.")
        @Key("charge-materials")
        boolean chargeMaterials,

        @In("wallsroads/route") @Title("Steepest climb") @Range(min = 1, max = 4)
        @Describe("The most blocks a road may rise or fall per block travelled. 1 is a road; higher "
                + "is a staircase.")
        @Key("max-grade")
        int maxGrade,

        @In("wallsroads/route") @Title("Terrain smoothing") @Range(min = 0, max = 10)
        @Describe("How many columns either side the ground height is averaged over, so a single "
                + "boulder does not put a step in the road.")
        @Key("terrain-smoothing")
        int terrainSmoothing,

        @In("wallsroads/route") @Title("Bridge when this far up") @Range(min = 1, max = 16)
        @Describe("How far above the ground the road has to run before it is built as a bridge, with "
                + "a railing and piers.")
        @Key("bridge-min-gap")
        int bridgeMinGap,

        @In("wallsroads/route") @Title("Tunnel when this deep") @Range(min = 1, max = 16)
        @Describe("How far below the surface the road has to run before it is bored out as a lined, "
                + "lit tunnel rather than a trench.")
        @Key("tunnel-min-cover")
        int tunnelMinCover,

        @In("wallsroads/route") @Title("Longest bridge span") @Range(min = 4, max = 256)
        @Describe("The widest gap a road will hold level across. Anything wider it goes down into — "
                + "a bridge whose far end is out of sight is not a bridge.")
        @Key("max-bridge-span")
        int maxBridgeSpan,

        @In("wallsroads/route") @Title("Sea tunnel from this long") @Range(min = 4, max = 512)
        @Describe("How long a water crossing has to be before the road goes under it in a glass "
                + "tunnel instead of over it on a bridge.")
        @Key("sea-tunnel-min-length")
        int seaTunnelMinLength,

        @In("wallsroads/route") @Title("Sea tunnel from this deep") @Range(min = 2, max = 64)
        @Describe("And how deep that water has to be. A long shallow crossing is still a causeway.")
        @Key("sea-tunnel-min-depth")
        int seaTunnelMinDepth,

        @In("wallsroads/road") @Title("Built roads are quicker to walk")
        @Describe("On gives a small speed bonus to anybody walking on a road this module built. It "
                + "is what makes a road network worth extending rather than only worth looking at.")
        @Key("road-speed-bonus")
        boolean roadSpeedBonus,

        @In("wallsroads/gate") @Title("Gates may shut at night")
        @Describe("On lets a wall's owner have its gates close themselves at dusk and open at dawn. "
                + "Off keeps every gate as it was left, on every wall.")
        @Key("night-curfew-allowed")
        boolean nightCurfewAllowed,

        @In("wallsroads/build") @Title("Marking marker block")
        @Describe("What a clicked corner is shown as while you are marking something out. Shown to "
                + "you alone and never placed in the world — a light-emitting block reads best.")
        @Key("selection-marker-material")
        Material selectionMarkerMaterial,

        @In("wallsroads/build") @Title("Marking stick material")
        @Describe("The item handed out to mark a wall or road's outline.")
        @Key("selection-stick-material")
        Material selectionStickMaterial) {

    public static final WallsRoadsSettings DEFAULTS = new WallsRoadsSettings(
            true, Material.STONE_BRICKS, 8, 3, 0,
            Material.GRAVEL, 5,
            4, true,
            512, 200,
            3, false,
            1, 3, 2, 2, 64, 24, 6,
            true, true,
            Material.SEA_LANTERN,
            Material.STICK);

    /** What {@link de.raindancer.modules.wallsroads.service.RouteProfiler} needs, from what an owner set. */
    public de.raindancer.modules.wallsroads.service.RouteProfiler.Rules routeRules() {
        return new de.raindancer.modules.wallsroads.service.RouteProfiler.Rules(
                maxGrade, terrainSmoothing, bridgeMinGap, tunnelMinCover,
                maxBridgeSpan, seaTunnelMinLength, seaTunnelMinDepth);
    }

    public int curvinessClamped() {
        return Math.max(0, Math.min(5, roadCurviness));
    }

    public int wallHeight() {
        return Math.max(1, Math.min(320, defaultWallHeight));
    }

    public int wallThickness() {
        return Math.max(1, Math.min(5, defaultWallThickness));
    }

    public int cornerRadius() {
        return Math.max(0, Math.min(32, defaultCornerRadius));
    }

    public int roadWidth() {
        return Math.max(1, Math.min(32, defaultRoadWidth));
    }

    public int gateHeight() {
        return Math.max(1, Math.min(16, defaultGateHeight));
    }

    public int blocksPerBatchClamped() {
        return Math.max(1, Math.min(10000, blocksPerBatch));
    }

    public int maxVerticesClamped() {
        return Math.max(3, Math.min(500, maxVertices));
    }

    // The components an owner changes from a screen. Positional constructors in a record this wide
    // are how a "change the height" click silently changes the thickness instead.

    public WallsRoadsSettings withOpenCreation(boolean open) {
        return copy(b -> b.openCreation = open);
    }

    public WallsRoadsSettings withDefaultWallMaterial(Material material) {
        return copy(b -> b.defaultWallMaterial = material);
    }

    public WallsRoadsSettings withDefaultWallHeight(int height) {
        return copy(b -> b.defaultWallHeight = height);
    }

    public WallsRoadsSettings withDefaultWallThickness(int thickness) {
        return copy(b -> b.defaultWallThickness = thickness);
    }

    public WallsRoadsSettings withDefaultCornerRadius(int radius) {
        return copy(b -> b.defaultCornerRadius = radius);
    }

    public WallsRoadsSettings withDefaultRoadMaterial(Material material) {
        return copy(b -> b.defaultRoadMaterial = material);
    }

    public WallsRoadsSettings withDefaultRoadWidth(int width) {
        return copy(b -> b.defaultRoadWidth = width);
    }

    public WallsRoadsSettings withDefaultGateHeight(int height) {
        return copy(b -> b.defaultGateHeight = height);
    }

    public WallsRoadsSettings withAutoPlaceSigns(boolean enabled) {
        return copy(b -> b.autoPlaceSigns = enabled);
    }

    public WallsRoadsSettings withRoadCurviness(int passes) {
        return copy(b -> b.roadCurviness = passes);
    }

    public WallsRoadsSettings withChargeMaterials(boolean charge) {
        return copy(b -> b.chargeMaterials = charge);
    }

    public WallsRoadsSettings withMaxGrade(int grade) {
        return copy(b -> b.maxGrade = grade);
    }

    public WallsRoadsSettings withSeaTunnelMinLength(int length) {
        return copy(b -> b.seaTunnelMinLength = length);
    }

    public WallsRoadsSettings withSeaTunnelMinDepth(int depth) {
        return copy(b -> b.seaTunnelMinDepth = depth);
    }

    public WallsRoadsSettings withMaxBridgeSpan(int span) {
        return copy(b -> b.maxBridgeSpan = span);
    }

    public WallsRoadsSettings withRoadSpeedBonus(boolean bonus) {
        return copy(b -> b.roadSpeedBonus = bonus);
    }

    public WallsRoadsSettings withNightCurfewAllowed(boolean allowed) {
        return copy(b -> b.nightCurfewAllowed = allowed);
    }

    private WallsRoadsSettings copy(java.util.function.Consumer<Draft> change) {
        Draft draft = new Draft(this);
        change.accept(draft);
        return draft.build();
    }

    /** A mutable stand-in, so one changed component cannot be written into the wrong position. */
    private static final class Draft {
        private boolean openCreation;
        private Material defaultWallMaterial;
        private int defaultWallHeight;
        private int defaultWallThickness;
        private int defaultCornerRadius;
        private Material defaultRoadMaterial;
        private int defaultRoadWidth;
        private int roadCurviness;
        private boolean chargeMaterials;
        private int maxGrade;
        private int terrainSmoothing;
        private int bridgeMinGap;
        private int tunnelMinCover;
        private int maxBridgeSpan;
        private int seaTunnelMinLength;
        private int seaTunnelMinDepth;
        private boolean roadSpeedBonus;
        private boolean nightCurfewAllowed;
        private Material selectionMarkerMaterial;
        private int defaultGateHeight;
        private boolean autoPlaceSigns;
        private int blocksPerBatch;
        private int maxVertices;
        private Material selectionStickMaterial;

        Draft(WallsRoadsSettings from) {
            openCreation = from.openCreation();
            defaultWallMaterial = from.defaultWallMaterial();
            defaultWallHeight = from.defaultWallHeight();
            defaultWallThickness = from.defaultWallThickness();
            defaultCornerRadius = from.defaultCornerRadius();
            defaultRoadMaterial = from.defaultRoadMaterial();
            defaultRoadWidth = from.defaultRoadWidth();
            roadCurviness = from.roadCurviness();
            chargeMaterials = from.chargeMaterials();
            maxGrade = from.maxGrade();
            terrainSmoothing = from.terrainSmoothing();
            bridgeMinGap = from.bridgeMinGap();
            tunnelMinCover = from.tunnelMinCover();
            maxBridgeSpan = from.maxBridgeSpan();
            seaTunnelMinLength = from.seaTunnelMinLength();
            seaTunnelMinDepth = from.seaTunnelMinDepth();
            roadSpeedBonus = from.roadSpeedBonus();
            nightCurfewAllowed = from.nightCurfewAllowed();
            selectionMarkerMaterial = from.selectionMarkerMaterial();
            defaultGateHeight = from.defaultGateHeight();
            autoPlaceSigns = from.autoPlaceSigns();
            blocksPerBatch = from.blocksPerBatch();
            maxVertices = from.maxVertices();
            selectionStickMaterial = from.selectionStickMaterial();
        }

        WallsRoadsSettings build() {
            return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight,
                    defaultWallThickness, defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth,
                    defaultGateHeight, autoPlaceSigns, blocksPerBatch, maxVertices,
                    roadCurviness, chargeMaterials, maxGrade, terrainSmoothing, bridgeMinGap,
                    tunnelMinCover, maxBridgeSpan, seaTunnelMinLength, seaTunnelMinDepth,
                    roadSpeedBonus, nightCurfewAllowed, selectionMarkerMaterial,
                    selectionStickMaterial);
        }
    }
}
