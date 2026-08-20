package de.raindancer.modules.xaeromap.rules;

import de.raindancer.core.world.poi.Poi;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Whether this player may be offered this place as a waypoint.
 *
 * <p>The answer has to be the same one the plugin that owns the place would give, because a waypoint is
 * a set of coordinates: offering somebody the staff warp they may not use tells them where it is, and
 * no amount of refusing the teleport afterwards takes that back.
 *
 * <ul>
 *   <li><b>A home is its owner's.</b> No sharing, no staff exception — an admin who wants somebody's
 *       home coordinates has the admin tools for that, and getting them silently on a map is not the
 *       same thing as looking them up.</li>
 *   <li><b>A warp is whoever its permission says.</b> RainsCore keeps one permission on a place and
 *       {@code warp-module} stores all three of its access kinds in it, so asking that one node here
 *       gives exactly the same answer as the warp screen does — rather than a second copy of the rule
 *       that can be more generous than the first.</li>
 * </ul>
 */
public final class WaypointVisibilityRule implements IXaeroMapRule {

    /**
     * Where a place keeps the permission needed to use it.
     *
     * <p>RainsCore's own tag convention, which {@code warp-module} writes and reads; named here rather
     * than imported so this module still builds and runs on a server with no warps plugin.
     */
    public static final String PERMISSION_TAG = "permission";

    private final Predicate<String> holds;

    /**
     * @param holds whether the viewer has a permission node. A predicate rather than a {@code Player},
     *              so the rule stays a pure question and can be asked without a server
     */
    public WaypointVisibilityRule(Predicate<String> holds) {
        this.holds = holds == null ? node -> false : holds;
    }

    public boolean mayHave(UUID viewer, Poi place) {
        if (place == null || viewer == null) {
            return false;
        }
        if (viewer.equals(place.owner())) {
            return true;
        }
        String permission = place.tag(PERMISSION_TAG).orElse("");
        if (!permission.isBlank()) {
            return holds.test(permission);
        }
        // Somebody else's place with no permission on it: theirs if it is private, everybody's if its
        // owner marked it shared. A place with no owner at all — a warp the server itself made — is
        // shared by construction.
        return place.shared() || place.owner() == null;
    }

    @Override
    public String describe() {
        return "whether a player may be offered a place as a waypoint on their own map";
    }
}
