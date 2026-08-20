package de.raindancer.modules.xaeromap.rules;

import de.raindancer.core.world.poi.Poi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the answer here is the same one the plugin owning the place would give.
 *
 * <p>A waypoint is coordinates. Offering the wrong person one is not a cosmetic bug: it tells them
 * where something is, and nothing said afterwards untells them.
 */
class WaypointVisibilityRuleTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();

    private final WaypointVisibilityRule openHanded = new WaypointVisibilityRule(node -> true);
    private final WaypointVisibilityRule tightFisted = new WaypointVisibilityRule(node -> false);

    private static Poi place(UUID owner, boolean shared, String permission) {
        Poi.Builder builder = Poi.builder("Somewhere", "world", 0, 64, 0)
                .kind("home").owner(owner).shared(shared);
        if (permission != null) {
            builder.tag(WaypointVisibilityRule.PERMISSION_TAG, permission);
        }
        return builder.build();
    }

    @Test
    @DisplayName("your own place is yours whatever else is true of it")
    void ownersAlwaysMay() {
        assertThat(tightFisted.mayHave(VIEWER, place(VIEWER, false, null))).isTrue();
        assertThat(tightFisted.mayHave(VIEWER, place(VIEWER, false, "some.node"))).isTrue();
    }

    @Test
    @DisplayName("somebody else's private place is not")
    void othersAreNot() {
        assertThat(openHanded.mayHave(VIEWER, place(SOMEBODY_ELSE, false, null))).isFalse();
    }

    @Test
    @DisplayName("a place its owner shared is everybody's")
    void sharedIsShared() {
        assertThat(tightFisted.mayHave(VIEWER, place(SOMEBODY_ELSE, true, null))).isTrue();
    }

    @Test
    @DisplayName("a place with a permission on it follows that permission, shared or not")
    void thepermissionDecides() {
        assertThat(tightFisted.mayHave(VIEWER, place(null, true, "warp.staff"))).isFalse();
        assertThat(openHanded.mayHave(VIEWER, place(null, true, "warp.staff"))).isTrue();
        assertThat(tightFisted.mayHave(VIEWER, place(SOMEBODY_ELSE, false, "warp.staff")))
                .as("the permission has the last word over sharing, because that is how warp-module "
                        + "stores all three of its access kinds")
                .isFalse();
    }

    @Test
    @DisplayName("a place the server itself made, with no permission, is everybody's")
    void ownerlessPlacesAreOpen() {
        assertThat(tightFisted.mayHave(VIEWER, place(null, false, null)))
                .as("a plain /setwarp spawn has no owner and no permission, and everybody uses it")
                .isTrue();
    }

    @Test
    @DisplayName("nobody in particular may have nothing in particular")
    void nullsRefuse() {
        assertThat(openHanded.mayHave(null, place(null, true, null))).isFalse();
        assertThat(openHanded.mayHave(VIEWER, null)).isFalse();
    }

    @Test
    @DisplayName("with no way to check permissions, a restricted place stays restricted")
    void theFallbackIsTheSafeOne() {
        assertThat(new WaypointVisibilityRule(null).mayHave(VIEWER, place(null, true, "warp.staff")))
                .isFalse();
    }

    @Test
    @DisplayName("the rule says what it decides, for the diagnostic that lists rules")
    void itDescribesItself() {
        assertThat(openHanded.describe()).isNotBlank();
    }
}
