package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure geometry, no Bukkit needed at all — mirrors {@code hungergames-module}'s own
 * {@code LobbyBoxServiceTest} in shape.
 */
class ManhuntLobbyBoxTest {

    private static final ManhuntSettings ACTIVE = ManhuntSettings.DEFAULTS
            .withLobbySpawnSet(true)
            .withLobbyWorldName("hunt")
            .withLobbyX(100.0)
            .withLobbyY(64.0)
            .withLobbyZ(-50.0)
            .withLobbyYaw(180.0)
            .withLobbyRadius(15);

    @Nested
    @DisplayName("isActive")
    class Active {

        @Test
        @DisplayName("inactive when lobbySpawnSet is false")
        void inactiveWhenNotSet() {
            ManhuntLobbyBox box = new ManhuntLobbyBox(ManhuntSettings.DEFAULTS.withLobbySpawnSet(false)
                    .withLobbyWorldName("hunt"));

            assertThat(box.isActive()).isFalse();
        }

        @Test
        @DisplayName("inactive when the world name is blank, even if lobbySpawnSet is true")
        void inactiveWhenWorldBlank() {
            ManhuntLobbyBox box = new ManhuntLobbyBox(ManhuntSettings.DEFAULTS.withLobbySpawnSet(true)
                    .withLobbyWorldName(""));

            assertThat(box.isActive()).isFalse();
        }

        @Test
        @DisplayName("active when both are set")
        void activeWhenBothSet() {
            ManhuntLobbyBox box = new ManhuntLobbyBox(ACTIVE);

            assertThat(box.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("isInside")
    class Inside {

        private final ManhuntLobbyBox box = new ManhuntLobbyBox(ACTIVE);

        @Test
        @DisplayName("true at the exact centre")
        void trueAtCentre() {
            assertThat(box.isInside(new ManhuntLobbyBox.Point("hunt", 100.0, 64.0, -50.0))).isTrue();
        }

        @Test
        @DisplayName("true right at the radius boundary")
        void trueAtBoundary() {
            assertThat(box.isInside(new ManhuntLobbyBox.Point("hunt", 115.0, 79.0, -65.0))).isTrue();
        }

        @Test
        @DisplayName("false one unit past the boundary")
        void falseOnePastBoundary() {
            assertThat(box.isInside(new ManhuntLobbyBox.Point("hunt", 116.0, 64.0, -50.0))).isFalse();
        }

        @Test
        @DisplayName("false in a different world, even at the same coordinates")
        void falseInDifferentWorld() {
            assertThat(box.isInside(new ManhuntLobbyBox.Point("other", 100.0, 64.0, -50.0))).isFalse();
        }
    }

    @Nested
    @DisplayName("forbidsCombatBetween")
    class Combat {

        private final ManhuntLobbyBox box = new ManhuntLobbyBox(ACTIVE);
        private final ManhuntLobbyBox.Point inside = new ManhuntLobbyBox.Point("hunt", 100.0, 64.0, -50.0);
        private final ManhuntLobbyBox.Point outside = new ManhuntLobbyBox.Point("hunt", 1000.0, 64.0, -50.0);

        @Test
        @DisplayName("true when the attacker is inside")
        void trueWhenAttackerInside() {
            assertThat(box.forbidsCombatBetween(inside, outside)).isTrue();
        }

        @Test
        @DisplayName("true when the victim is inside")
        void trueWhenVictimInside() {
            assertThat(box.forbidsCombatBetween(outside, inside)).isTrue();
        }

        @Test
        @DisplayName("false when both are outside")
        void falseWhenBothOutside() {
            assertThat(box.forbidsCombatBetween(outside, outside)).isFalse();
        }

        @Test
        @DisplayName("false outright when the box is inactive, even standing at the same spot")
        void falseWhenInactive() {
            ManhuntLobbyBox inactive = new ManhuntLobbyBox(ManhuntSettings.DEFAULTS);

            assertThat(inactive.forbidsCombatBetween(inside, inside)).isFalse();
        }
    }

    @Nested
    @DisplayName("spawnPoint / spawnYaw")
    class Spawn {

        @Test
        @DisplayName("empty when not active")
        void emptyWhenInactive() {
            ManhuntLobbyBox box = new ManhuntLobbyBox(ManhuntSettings.DEFAULTS);

            assertThat(box.spawnPoint()).isEmpty();
        }

        @Test
        @DisplayName("the configured point and yaw when active")
        void presentWhenActive() {
            ManhuntLobbyBox box = new ManhuntLobbyBox(ACTIVE);

            assertThat(box.spawnPoint()).contains(new ManhuntLobbyBox.Point("hunt", 100.0, 64.0, -50.0));
            assertThat(box.spawnYaw()).isEqualTo(180.0);
        }
    }

    @Test
    @DisplayName("settings can be updated live, the way a SettingsStore.onChange listener would")
    void settingsUpdateLive() {
        ManhuntLobbyBox box = new ManhuntLobbyBox(ManhuntSettings.DEFAULTS);
        assertThat(box.isActive()).isFalse();

        box.settings(ACTIVE);

        assertThat(box.isActive()).isTrue();
        assertThat(box.spawnPoint()).isEqualTo(Optional.of(new ManhuntLobbyBox.Point("hunt", 100.0, 64.0, -50.0)));
    }
}
