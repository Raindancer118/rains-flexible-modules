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
})
public record WallsRoadsSettings(

        @In("wallsroads/access") @Title("Anybody may mark out a wall or road")
        @Describe("On lets any player use /wallsroads wall new or /wallsroads road new. Off gates "
                + "it behind the create permission, for a server that wants only builders placing them.")
        @Key("open-creation")
        boolean openCreation,

        @In("wallsroads/wall") @Title("Default wall material")
        @Describe("What a freshly marked wall builds from until its owner picks something else.")
        @Key("default-wall-material")
        Material defaultWallMaterial,

        @In("wallsroads/wall") @Title("Default wall height") @Range(min = 1, max = 320)
        @Describe("Blocks, measured up from the marked base.")
        @Key("default-wall-height")
        int defaultWallHeight,

        @In("wallsroads/wall") @Title("Default wall thickness") @Range(min = 1, max = 5)
        @Describe("Blocks, measured outward from the marked outline.")
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

        @In("wallsroads/build") @Title("Marking stick material")
        @Describe("The item handed out to mark a wall or road's outline.")
        @Key("selection-stick-material")
        Material selectionStickMaterial) {

    public static final WallsRoadsSettings DEFAULTS = new WallsRoadsSettings(
            true, Material.STONE_BRICKS, 6, 1, 0,
            Material.GRAVEL, 5,
            4, true,
            512, 200,
            Material.STICK);

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

    public WallsRoadsSettings withOpenCreation(boolean open) {
        return new WallsRoadsSettings(open, defaultWallMaterial, defaultWallHeight, defaultWallThickness,
                defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultWallMaterial(Material material) {
        return new WallsRoadsSettings(openCreation, material, defaultWallHeight, defaultWallThickness,
                defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultWallHeight(int height) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, height, defaultWallThickness,
                defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultWallThickness(int thickness) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight, thickness,
                defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultCornerRadius(int radius) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight, defaultWallThickness,
                radius, defaultRoadMaterial, defaultRoadWidth, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultRoadMaterial(Material material) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight, defaultWallThickness,
                defaultCornerRadius, material, defaultRoadWidth, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultRoadWidth(int width) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight, defaultWallThickness,
                defaultCornerRadius, defaultRoadMaterial, width, defaultGateHeight,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withDefaultGateHeight(int height) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight, defaultWallThickness,
                defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth, height,
                autoPlaceSigns, blocksPerBatch, maxVertices, selectionStickMaterial);
    }

    public WallsRoadsSettings withAutoPlaceSigns(boolean enabled) {
        return new WallsRoadsSettings(openCreation, defaultWallMaterial, defaultWallHeight, defaultWallThickness,
                defaultCornerRadius, defaultRoadMaterial, defaultRoadWidth, defaultGateHeight,
                enabled, blocksPerBatch, maxVertices, selectionStickMaterial);
    }
}
