package de.raindancer.modules.claims.selection;


import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A player's in-progress claim outline.
 * <p>
 * Both selection modes end up as a polygon; rectangle mode simply auto-completes after the second
 * click. The vertical range is tracked separately so a player can pull a claim underground without
 * touching its footprint.
 */
public final class Selection {

    public enum Mode {
        RECTANGLE, POLYGON;

        public Mode other() {
            return this == RECTANGLE ? POLYGON : RECTANGLE;
        }
    }

    /** What the finished selection will be used for. */
    public enum Purpose {
        NEW_CLAIM, RESIZE_CLAIM, NO_CLAIM_ZONE
    }

    private final UUID worldId;
    private final String worldName;
    private Mode mode;
    private Purpose purpose = Purpose.NEW_CLAIM;
    /** Set when resizing an existing claim. */
    private UUID targetClaimId;
    /** Set when creating or resizing a no-claim zone. */
    private String targetZoneName;
    /** Pending claim name, filled in before the stick is handed out. */
    private String pendingName;

    private final List<ClaimPoint> points = new ArrayList<>();
    /** Y of each clicked block, parallel to {@link #points} — used to place the glowing markers. */
    private final List<Integer> pointHeights = new ArrayList<>();
    /** Lowest and highest Y the player clicked; used by the SELECTION vertical modes. */
    private Integer clickedMinY;
    private Integer clickedMaxY;
    /** Explicit overrides set through the GUI or command. */
    private Integer minY;
    private Integer maxY;

    private final int worldMinY;
    private final int worldMaxY;
    private long lastInteraction = System.currentTimeMillis();

    public Selection(UUID worldId, String worldName, Mode mode, int worldMinY, int worldMaxY) {
        this.worldId = worldId;
        this.worldName = worldName;
        this.mode = mode;
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
        touch();
    }

    public Purpose purpose() {
        return purpose;
    }

    public void purpose(Purpose purpose) {
        this.purpose = purpose;
    }

    public UUID targetClaimId() {
        return targetClaimId;
    }

    public void targetClaimId(UUID id) {
        this.targetClaimId = id;
    }

    public String targetZoneName() {
        return targetZoneName;
    }

    public void targetZoneName(String name) {
        this.targetZoneName = name;
    }



    public String pendingName() {
        return pendingName;
    }

    public void pendingName(String name) {
        this.pendingName = name;
    }

    public List<ClaimPoint> points() {
        return List.copyOf(points);
    }

    public int pointCount() {
        return points.size();
    }

    public int worldMinY() {
        return worldMinY;
    }

    public int worldMaxY() {
        return worldMaxY;
    }

    public long lastInteraction() {
        return lastInteraction;
    }

    private void touch() {
        lastInteraction = System.currentTimeMillis();
    }

    /**
     * Adds a clicked block. In rectangle mode the two most recent clicks are kept as opposite corners so
     * a player can keep adjusting without clearing the selection.
     */
    public void addPoint(int x, int y, int z) {
        ClaimPoint point = new ClaimPoint(x, z);
        if (mode == Mode.RECTANGLE) {
            if (points.size() >= 2) {
                points.clear();
                pointHeights.clear();
                clickedMinY = null;
                clickedMaxY = null;
            }
            points.add(point);
            pointHeights.add(y);
        } else {
            if (!points.isEmpty() && points.get(points.size() - 1).equals(point)) {
                return;
            }
            points.add(point);
            pointHeights.add(y);
        }
        clickedMinY = clickedMinY == null ? y : Math.min(clickedMinY, y);
        clickedMaxY = clickedMaxY == null ? y : Math.max(clickedMaxY, y);
        touch();
    }

    public boolean removeLastPoint() {
        if (points.isEmpty()) {
            return false;
        }
        points.remove(points.size() - 1);
        if (!pointHeights.isEmpty()) {
            pointHeights.remove(pointHeights.size() - 1);
        }
        touch();
        return true;
    }

    public void clearPoints() {
        points.clear();
        pointHeights.clear();
        clickedMinY = null;
        clickedMaxY = null;
        touch();
    }

    /** The Y the given corner was clicked at, if it is known. */
    public Optional<Integer> clickedYAt(int index) {
        if (index < 0 || index >= pointHeights.size()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pointHeights.get(index));
    }

    public Optional<Integer> clickedMinY() {
        return Optional.ofNullable(clickedMinY);
    }

    public Optional<Integer> clickedMaxY() {
        return Optional.ofNullable(clickedMaxY);
    }

    public Optional<Integer> explicitMinY() {
        return Optional.ofNullable(minY);
    }

    public Optional<Integer> explicitMaxY() {
        return Optional.ofNullable(maxY);
    }

    public void minY(Integer value) {
        this.minY = value == null ? null : clamp(value);
        touch();
    }

    public void maxY(Integer value) {
        this.maxY = value == null ? null : clamp(value);
        touch();
    }

    public void extendToWorldLimits() {
        this.minY = worldMinY;
        this.maxY = worldMaxY;
        touch();
    }

    private int clamp(int y) {
        return Math.max(worldMinY, Math.min(worldMaxY, y));
    }

    /** True once enough points exist for the current mode. */
    public boolean isComplete() {
        return mode == Mode.RECTANGLE ? points.size() == 2 : points.size() >= 3;
    }

    /**
     * Builds the shape. {@code resolvedMinY}/{@code resolvedMaxY} come from
     * {@link SelectionService#resolveVerticalRange(Selection)} so the vertical policy lives in one place.
     */
    public ClaimShape toShape(int resolvedMinY, int resolvedMaxY) {
        if (!isComplete()) {
            throw new IllegalStateException("Selection is not complete");
        }
        if (mode == Mode.RECTANGLE) {
            ClaimPoint first = points.get(0);
            ClaimPoint second = points.get(1);
            return ClaimShape.rectangle(first.x(), first.z(), second.x(), second.z(), resolvedMinY, resolvedMaxY);
        }
        return new ClaimShape(points, resolvedMinY, resolvedMaxY);
    }
}
