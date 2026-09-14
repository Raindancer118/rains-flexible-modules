package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.SpeedrunCompanions.Companion;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry a module built <em>on top of</em> this one uses to put itself on the compass' screen.
 *
 * <h2>Why this is a test at all, when it is a map with three methods</h2>
 * Because the whole point of it is that {@code speedrun-module} never learns the companion exists:
 * the failure mode is not a wrong button but a stale one — a module that was unloaded and left its
 * entry behind, so a click reaches into a plugin that has already gone. Registering, replacing and
 * withdrawing are therefore all checked, and the order is pinned so a page cannot reshuffle itself
 * between two openings.
 */
class SpeedrunCompanionsTest {

    private static Companion companion(String id, String label) {
        return new Companion(id, label, "<gray>" + label, Material.COMPASS, (viewer, parent) -> {
        });
    }

    @BeforeEach
    @AfterEach
    void emptyRegistry() {
        SpeedrunCompanions.clear();
    }

    @Test
    @DisplayName("nothing is offered until a companion module offers it")
    void emptyByDefault() {
        assertThat(SpeedrunCompanions.offered()).isEmpty();
    }

    @Test
    @DisplayName("an offered companion is listed")
    void offered() {
        Companion manhunt = companion("manhunt", "Manhunt");

        SpeedrunCompanions.offer(manhunt);

        assertThat(SpeedrunCompanions.offered()).containsExactly(manhunt);
    }

    @Test
    @DisplayName("withdrawing takes it back off the page")
    void withdrawn() {
        SpeedrunCompanions.offer(companion("manhunt", "Manhunt"));

        SpeedrunCompanions.withdraw("manhunt");

        assertThat(SpeedrunCompanions.offered()).isEmpty();
    }

    @Test
    @DisplayName("withdrawing something never offered is not an error")
    void withdrawUnknown() {
        SpeedrunCompanions.withdraw("nothing-like-this");

        assertThat(SpeedrunCompanions.offered()).isEmpty();
    }

    @Test
    @DisplayName("offering the same id twice replaces rather than duplicates")
    void reofferReplaces() {
        SpeedrunCompanions.offer(companion("manhunt", "Manhunt"));
        SpeedrunCompanions.offer(companion("manhunt", "Manhunt 2"));

        assertThat(SpeedrunCompanions.offered()).extracting(Companion::label)
                .containsExactly("Manhunt 2");
    }

    @Test
    @DisplayName("the order is the ids' own, so the page does not reshuffle between openings")
    void stableOrder() {
        SpeedrunCompanions.offer(companion("manhunt", "Manhunt"));
        SpeedrunCompanions.offer(companion("chained", "Chained"));

        List<String> ids = SpeedrunCompanions.offered().stream().map(Companion::id).toList();

        assertThat(ids).containsExactly("chained", "manhunt");
    }

    @Test
    @DisplayName("a companion without an id or an opener is refused at the door")
    void refusesNonsense() {
        assertThatThrownBy(() -> SpeedrunCompanions.offer(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SpeedrunCompanions.offer(
                new Companion(" ", "Manhunt", "", Material.COMPASS, (viewer, parent) -> {
                })))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpeedrunCompanions.offer(
                new Companion("manhunt", "Manhunt", "", Material.COMPASS, null)))
                .isInstanceOf(NullPointerException.class);
    }
}
