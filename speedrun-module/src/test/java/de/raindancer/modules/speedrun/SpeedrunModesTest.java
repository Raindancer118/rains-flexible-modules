package de.raindancer.modules.speedrun;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shelf a game mode built on top of this module puts itself on.
 *
 * <p>The failure that matters is a stale entry: a mode whose module was unloaded and left itself
 * behind, so the next start reaches into a plugin that has already gone. Offering, replacing and
 * withdrawing are therefore all pinned, and so is the order the lobby menu cycles through.
 */
class SpeedrunModesTest {

    static SpeedrunMode mode(String id) {
        return new SpeedrunMode() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public Material icon() {
                return Material.TARGET;
            }

            @Override
            public Optional<String> refuseStart(SpeedrunSettings config, Set<UUID> participants) {
                return Optional.empty();
            }

            @Override
            public void onStart(SpeedrunRun run) {
            }
        };
    }

    @BeforeEach
    @AfterEach
    void emptyShelf() {
        SpeedrunModes.clear();
    }

    @Test
    @DisplayName("nothing is offered until a module offers it")
    void emptyByDefault() {
        assertThat(SpeedrunModes.offered()).isEmpty();
        assertThat(SpeedrunModes.find("manhunt")).isEmpty();
    }

    @Test
    @DisplayName("an offered mode is found by its id, whatever case the setting was written in")
    void foundById() {
        SpeedrunMode manhunt = mode("manhunt");
        SpeedrunModes.offer(manhunt);

        assertThat(SpeedrunModes.find("manhunt")).containsSame(manhunt);
        assertThat(SpeedrunModes.find("Manhunt")).containsSame(manhunt);
    }

    @Test
    @DisplayName("the plain speedrun is no mode at all — a blank id finds nothing")
    void blankIsThePlainRace() {
        SpeedrunModes.offer(mode("manhunt"));

        assertThat(SpeedrunModes.find("")).isEmpty();
        assertThat(SpeedrunModes.find(null)).isEmpty();
    }

    @Test
    @DisplayName("offering the same id again replaces the first")
    void replaces() {
        SpeedrunModes.offer(mode("manhunt"));
        SpeedrunMode second = mode("manhunt");
        SpeedrunModes.offer(second);

        assertThat(SpeedrunModes.offered()).containsExactly(second);
    }

    @Test
    @DisplayName("a withdrawn mode is gone")
    void withdraws() {
        SpeedrunModes.offer(mode("manhunt"));

        SpeedrunModes.withdraw("manhunt");

        assertThat(SpeedrunModes.find("manhunt")).isEmpty();
    }

    @Test
    @DisplayName("offered in id order, so the menu cycles the same way every time")
    void ordered() {
        SpeedrunModes.offer(mode("zombies"));
        SpeedrunModes.offer(mode("manhunt"));

        assertThat(SpeedrunModes.offered()).extracting(SpeedrunMode::id)
                .containsExactly("manhunt", "zombies");
    }

    @Test
    @DisplayName("the menu's cycle runs plain → every mode → plain")
    void cycle() {
        SpeedrunModes.offer(mode("manhunt"));
        SpeedrunModes.offer(mode("zombies"));

        assertThat(SpeedrunModes.next("")).isEqualTo("manhunt");
        assertThat(SpeedrunModes.next("manhunt")).isEqualTo("zombies");
        assertThat(SpeedrunModes.next("zombies")).isEqualTo("");
        assertThat(SpeedrunModes.next("uninstalled")).as("an unknown id starts over").isEqualTo("");
    }

    @Test
    @DisplayName("a mode without an id is refused — it could never be withdrawn")
    void needsAnId() {
        assertThatThrownBy(() -> SpeedrunModes.offer(mode(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SpeedrunModes.offered()).isEmpty();
    }
}
