package de.raindancer.modules.manhunt.service;

import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The curated Manhunt achievements: which nine exist, and the rules that award them — mirroring
 * {@code AchievementsTest}'s own way of standing up a real {@link Achievements} over a temporary
 * {@link Database} rather than faking the store underneath.
 */
class ManhuntAchievementsTest {

    @TempDir
    Path directory;

    private Database database;
    private Achievements achievements;
    private ManhuntAchievements manhuntAchievements;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        achievements = new Achievements(directory.resolve("achievements.yml"), database,
                new AtomicLong(1_000_000L)::get);
        manhuntAchievements = new ManhuntAchievements(achievements);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Nested
    @DisplayName("defining the curated set")
    class Defining {

        @Test
        @DisplayName("defines exactly the 9 keys, with the right points and hidden flags")
        void definesAllNine() {
            manhuntAchievements.defineAll();

            assertThat(achievements.ofPlugin("manhunt")).hasSize(9);

            assertThat(achievements.byKey("manhunt:first-hunt")).isPresent().hasValueSatisfying(a -> {
                assertThat(a.points()).isEqualTo(5);
                assertThat(a.hidden()).isFalse();
                assertThat(a.goal()).isEmpty();
            });
            assertThat(achievements.byKey("manhunt:runner-portal")).isPresent().hasValueSatisfying(a -> {
                assertThat(a.points()).isEqualTo(15);
                assertThat(a.hidden()).isFalse();
            });
            assertThat(achievements.byKey("manhunt:runner-advancement")).isPresent().hasValueSatisfying(a ->
                    assertThat(a.points()).isEqualTo(15));
            assertThat(achievements.byKey("manhunt:hunter-elimination")).isPresent().hasValueSatisfying(a ->
                    assertThat(a.points()).isEqualTo(15));
            assertThat(achievements.byKey("manhunt:hunter-timeout")).isPresent().hasValueSatisfying(a ->
                    assertThat(a.points()).isEqualTo(15));
            assertThat(achievements.byKey("manhunt:chaos-agent")).isPresent().hasValueSatisfying(a -> {
                assertThat(a.points()).isEqualTo(10);
                assertThat(a.goal()).contains(5);
                assertThat(a.hidden()).isFalse();
            });
            assertThat(achievements.byKey("manhunt:gatekeeper")).isPresent().hasValueSatisfying(a -> {
                assertThat(a.points()).isEqualTo(5);
                assertThat(a.hidden()).isFalse();
            });
            assertThat(achievements.byKey("manhunt:open-doors")).isPresent().hasValueSatisfying(a -> {
                assertThat(a.points()).isEqualTo(5);
                assertThat(a.hidden()).isTrue();
            });
            assertThat(achievements.byKey("manhunt:chaos-veteran")).isPresent().hasValueSatisfying(a -> {
                assertThat(a.hidden()).isTrue();
                assertThat(a.goal()).contains(20);
                assertThat(a.points()).isEqualTo(20);
            });
        }

        @Test
        @DisplayName("calling it twice does not duplicate anything, or undo an owner's edit")
        void idempotent() {
            manhuntAchievements.defineAll();
            achievements.define(achievements.byKey("manhunt:first-hunt").orElseThrow()
                    .withTitle("<red>Renamed by the owner"));

            manhuntAchievements.defineAll();

            assertThat(achievements.ofPlugin("manhunt")).hasSize(9);
            assertThat(achievements.byKey("manhunt:first-hunt").orElseThrow().title())
                    .isEqualTo("<red>Renamed by the owner");
        }
    }

    @Nested
    @DisplayName("awarding first-hunt")
    class FirstHunt {

        @BeforeEach
        void define() {
            manhuntAchievements.defineAll();
        }

        @Test
        @DisplayName("only whoever in the roster is currently online gets it")
        void awardsOnlyOnline() {
            UUID online = UUID.randomUUID();
            UUID offline = UUID.randomUUID();
            Player onlinePlayer = mock(Player.class);
            when(onlinePlayer.getUniqueId()).thenReturn(online);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(online)).thenReturn(onlinePlayer);
                bukkit.when(() -> Bukkit.getPlayer(offline)).thenReturn(null);

                manhuntAchievements.awardFirstHunt(Set.of(online, offline));
            }

            assertThat(achievements.hasEarned(online, "manhunt:first-hunt")).isTrue();
            assertThat(achievements.hasEarned(offline, "manhunt:first-hunt")).isFalse();
        }
    }

    @Nested
    @DisplayName("awarding a win")
    class Winning {

        private final UUID runner = UUID.randomUUID();
        private final UUID hunter = UUID.randomUUID();
        private ManhuntTeams teams;
        private Set<UUID> everybody;

        @BeforeEach
        void define() {
            manhuntAchievements.defineAll();
            teams = new ManhuntTeams(() -> false);
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            everybody = Set.of(runner, hunter);
        }

        private void stubOnline(MockedStatic<Bukkit> bukkit) {
            Player runnerPlayer = mock(Player.class);
            when(runnerPlayer.getUniqueId()).thenReturn(runner);
            Player hunterPlayer = mock(Player.class);
            when(hunterPlayer.getUniqueId()).thenReturn(hunter);
            bukkit.when(() -> Bukkit.getPlayer(runner)).thenReturn(runnerPlayer);
            bukkit.when(() -> Bukkit.getPlayer(hunter)).thenReturn(hunterPlayer);
        }

        @Test
        @DisplayName("portal-exit awards runner-portal to the Runners")
        void portalExit() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubOnline(bukkit);
                manhuntAchievements.awardWin(everybody, teams, "portal-exit");
            }
            assertThat(achievements.hasEarned(runner, "manhunt:runner-portal")).isTrue();
            assertThat(achievements.hasEarned(hunter, "manhunt:hunter-elimination")).isFalse();
        }

        @Test
        @DisplayName("advancement:<key> awards runner-advancement to the Runners")
        void advancement() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubOnline(bukkit);
                manhuntAchievements.awardWin(everybody, teams, "advancement:minecraft:end/kill_dragon");
            }
            assertThat(achievements.hasEarned(runner, "manhunt:runner-advancement")).isTrue();
        }

        @Test
        @DisplayName("all-runners-dead awards hunter-elimination to the Hunters")
        void allRunnersDead() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubOnline(bukkit);
                manhuntAchievements.awardWin(everybody, teams, "all-runners-dead");
            }
            assertThat(achievements.hasEarned(hunter, "manhunt:hunter-elimination")).isTrue();
            assertThat(achievements.hasEarned(runner, "manhunt:runner-portal")).isFalse();
        }

        @Test
        @DisplayName("timeout awards hunter-timeout to the Hunters")
        void timeout() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubOnline(bukkit);
                manhuntAchievements.awardWin(everybody, teams, "timeout");
            }
            assertThat(achievements.hasEarned(hunter, "manhunt:hunter-timeout")).isTrue();
        }

        @Test
        @DisplayName("manual, plugin-disable and an unrecognised reason award nobody")
        void noWinnerReasons() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubOnline(bukkit);
                manhuntAchievements.awardWin(everybody, teams, "manual");
                manhuntAchievements.awardWin(everybody, teams, "plugin-disable");
                manhuntAchievements.awardWin(everybody, teams, "something-else");
            }
            assertThat(achievements.earnedBy(runner)).isEmpty();
            assertThat(achievements.earnedBy(hunter)).isEmpty();
        }
    }

    @Nested
    @DisplayName("chaos progress")
    class ChaosProgress {

        @BeforeEach
        void define() {
            manhuntAchievements.defineAll();
        }

        @Test
        @DisplayName("5 throws earns chaos-agent, 20 earns chaos-veteran")
        void reachesBothGoals() {
            UUID id = UUID.randomUUID();
            Player thrower = mock(Player.class);
            when(thrower.getUniqueId()).thenReturn(id);

            for (int i = 0; i < 5; i++) {
                manhuntAchievements.progressChaos(thrower);
            }
            assertThat(achievements.hasEarned(id, "manhunt:chaos-agent")).isTrue();
            assertThat(achievements.hasEarned(id, "manhunt:chaos-veteran")).isFalse();

            for (int i = 0; i < 15; i++) {
                manhuntAchievements.progressChaos(thrower);
            }
            assertThat(achievements.hasEarned(id, "manhunt:chaos-veteran")).isTrue();
        }

        @Test
        @DisplayName("a null thrower is a no-op")
        void nullIsSafe() {
            assertThatCode(() -> manhuntAchievements.progressChaos(null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the whitelist achievements")
    class Whitelist {

        @BeforeEach
        void define() {
            manhuntAchievements.defineAll();
        }

        @Test
        @DisplayName("closing awards gatekeeper, once")
        void gatekeeper() {
            UUID id = UUID.randomUUID();
            Player closer = mock(Player.class);
            when(closer.getUniqueId()).thenReturn(id);

            manhuntAchievements.awardGatekeeper(closer);
            manhuntAchievements.awardGatekeeper(closer);

            assertThat(achievements.hasEarned(id, "manhunt:gatekeeper")).isTrue();
        }

        @Test
        @DisplayName("opening awards open-doors, once")
        void openDoors() {
            UUID id = UUID.randomUUID();
            Player opener = mock(Player.class);
            when(opener.getUniqueId()).thenReturn(id);

            manhuntAchievements.awardOpenDoors(opener);
            manhuntAchievements.awardOpenDoors(opener);

            assertThat(achievements.hasEarned(id, "manhunt:open-doors")).isTrue();
        }

        @Test
        @DisplayName("nulls do not throw")
        void nullsAreSafe() {
            assertThatCode(() -> {
                manhuntAchievements.awardGatekeeper(null);
                manhuntAchievements.awardOpenDoors(null);
            }).doesNotThrowAnyException();
        }
    }
}
