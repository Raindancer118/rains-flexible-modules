package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.CrossWorldTracking;
import de.raindancer.modules.manhunt.ManhuntSettings.TrackerTargets;
import de.raindancer.modules.manhunt.service.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.service.TrackerCompass.Candidate;
import de.raindancer.modules.manhunt.service.TrackerCompass.Following;
import de.raindancer.modules.manhunt.service.TrackerCompass.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure targeting arithmetic, no Bukkit needed at all — the same shape {@code ManhuntLobbyBoxTest}
 * already has, and for the same reason: which Runner a Hunter's compass points at is a decision
 * about a few numbers and a world name, so it is tested without a server.
 */
class TrackerCompassTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());

    private static final Point HUNTER = new Point("hunt", 0, 64, 0);

    private static TrackerCompass compass() {
        return compass(ManhuntSettings.DEFAULTS, new PortalMemory());
    }

    private static TrackerCompass compass(ManhuntSettings settings, PortalMemory memory) {
        return new TrackerCompass(settings, memory);
    }

    private static Candidate at(UUID id, String world, double x, double z) {
        return new Candidate(id, new Point(world, x, 64, z));
    }

    @Nested
    @DisplayName("aim, in one world")
    class Aiming {

        @Test
        @DisplayName("nothing to point at with no Runners left")
        void noCandidates() {
            assertThat(compass().aim(HUNTER, List.of(), null).kind()).isEqualTo(Aim.Kind.NONE);
        }

        @Test
        @DisplayName("points at the only Runner in the same world")
        void singleCandidate() {
            Aim aim = compass().aim(HUNTER, List.of(at(ANNA, "hunt", 100, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.TRACKING);
            assertThat(aim.target()).isEqualTo(ANNA);
            assertThat(aim.at()).isEqualTo(new Point("hunt", 100, 64, 0));
            assertThat(aim.distance()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("with nobody picked, the nearest Runner in the same world wins")
        void nearestWins() {
            Aim aim = compass().aim(HUNTER,
                    List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30), at(CARO, "hunt", 500, 0)),
                    null);

            assertThat(aim.target()).isEqualTo(BEN);
            assertThat(aim.distance()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a Runner in this world beats one in another, however far away")
        void sameWorldBeatsOtherWorld() {
            Aim aim = compass().aim(HUNTER,
                    List.of(at(ANNA, "hunt_nether", 0, 0), at(BEN, "hunt", 9000, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.TRACKING);
            assertThat(aim.target()).isEqualTo(BEN);
        }

        @Test
        @DisplayName("switched off entirely, the compass never aims at anybody")
        void disabled() {
            TrackerCompass off = compass(ManhuntSettings.DEFAULTS.withTrackerCompassEnabled(false),
                    new PortalMemory());

            assertThat(off.aim(HUNTER, List.of(at(ANNA, "hunt", 10, 0)), null).kind())
                    .isEqualTo(Aim.Kind.NONE);
        }

        @Test
        @DisplayName("live settings are re-read, not captured once")
        void settingsAreLive() {
            TrackerCompass live = compass();
            live.settings(ManhuntSettings.DEFAULTS.withTrackerCompassEnabled(false));

            assertThat(live.aim(HUNTER, List.of(at(ANNA, "hunt", 10, 0)), null).kind())
                    .isEqualTo(Aim.Kind.NONE);
        }
    }

    @Nested
    @DisplayName("who the compass follows")
    class Targets {

        @Test
        @DisplayName("a Hunter's pick is kept even when somebody else is nearer")
        void chosenIsSticky() {
            Aim aim = compass().aim(HUNTER,
                    List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), Following.of(ANNA));

            assertThat(aim.target()).isEqualTo(ANNA);
        }

        @Test
        @DisplayName("a pick falls back to the nearest when that Runner is gone")
        void chosenGoneFallsBack() {
            Aim aim = compass().aim(HUNTER,
                    List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), Following.of(CARO));

            assertThat(aim.target()).isEqualTo(BEN);
        }

        @Test
        @DisplayName("a Hunter's own pick wins over a server default of NEAREST")
        void hunterPickBeatsTheServerDefault() {
            TrackerCompass nearest = compass(
                    ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.NEAREST),
                    new PortalMemory());

            Aim aim = nearest.aim(HUNTER, List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), Following.of(ANNA));

            assertThat(aim.target()).isEqualTo(ANNA);
        }

        @Test
        @DisplayName("with the Hunters unable to choose, a pick is ignored and the mode decides")
        void fixedIgnoresThePick() {
            TrackerCompass fixed = compass(ManhuntSettings.DEFAULTS
                            .withTrackerHunterMayChoose(false)
                            .withTrackerTargets(TrackerTargets.NEAREST),
                    new PortalMemory());

            Aim aim = fixed.aim(HUNTER, List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), Following.of(ANNA));

            assertThat(aim.target()).isEqualTo(BEN);
        }

        @Test
        @DisplayName("CHOSEN starts an untouched compass on the roster's first Runner, not the nearest")
        void chosenStartsLocked() {
            TrackerCompass locked = compass(
                    ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.CHOSEN),
                    new PortalMemory());

            Aim aim = locked.aim(HUNTER, List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), null);

            assertThat(aim.target()).isEqualTo(ANNA);
        }

        @Test
        @DisplayName("a Hunter who cycled back to the nearest gets the nearest, even under CHOSEN")
        void explicitNearestBeatsChosen() {
            TrackerCompass locked = compass(
                    ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.CHOSEN),
                    new PortalMemory());

            Aim aim = locked.aim(HUNTER, List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)),
                    Following.NEAREST);

            assertThat(aim.target()).isEqualTo(BEN);
        }

        @Test
        @DisplayName("CHOSEN and nobody allowed to choose is one Runner for every Hunter, all hunt")
        void fixedOnOneRunner() {
            TrackerCompass fixed = compass(ManhuntSettings.DEFAULTS
                            .withTrackerTargets(TrackerTargets.CHOSEN)
                            .withTrackerHunterMayChoose(false),
                    new PortalMemory());
            List<Candidate> roster = List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30));

            assertThat(fixed.aim(HUNTER, roster, Following.NEAREST).target()).isEqualTo(ANNA);
            assertThat(fixed.aim(HUNTER, roster, Following.of(BEN)).target()).isEqualTo(ANNA);
        }

        @Test
        @DisplayName("the starting point is the mode's, and it is a real position a click can leave")
        void startingPoint() {
            List<Candidate> roster = List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0));

            assertThat(compass().startingPoint(roster)).isEqualTo(Following.NEAREST);
            assertThat(compass(ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.CHOSEN),
                    new PortalMemory()).startingPoint(roster)).isEqualTo(Following.of(ANNA));
            assertThat(compass(ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.CHOSEN),
                    new PortalMemory()).startingPoint(List.of()))
                    .as("nobody to lock onto yet")
                    .isEqualTo(Following.NEAREST);
        }

        @Test
        @DisplayName("the right-click is gated by the Hunters-may-choose setting, not by the mode")
        void pickingIsGated() {
            assertThat(compass().allowsPicking()).isTrue();
            assertThat(compass(ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.NEAREST),
                    new PortalMemory()).allowsPicking())
                    .as("the server default has nothing to say about who may click")
                    .isTrue();
            assertThat(compass(ManhuntSettings.DEFAULTS.withTrackerHunterMayChoose(false),
                    new PortalMemory()).allowsPicking()).isFalse();
        }
    }

    @Nested
    @DisplayName("next")
    class Cycling {

        @Test
        @DisplayName("nobody to cycle to with no Runners left")
        void nothingToCycleTo() {
            assertThat(TrackerCompass.next(List.of(), Following.of(ANNA))).isEmpty();
        }

        @Test
        @DisplayName("cycling from the nearest lands on the first Runner")
        void fromNearest() {
            assertThat(TrackerCompass.next(List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0)), null))
                    .contains(Following.of(ANNA));
        }

        @Test
        @DisplayName("cycling steps to the next Runner in the roster")
        void stepsOn() {
            List<Candidate> roster = List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0),
                    at(CARO, "hunt", 0, 0));

            assertThat(TrackerCompass.next(roster, Following.of(ANNA))).contains(Following.of(BEN));
            assertThat(TrackerCompass.next(roster, Following.of(BEN))).contains(Following.of(CARO));
        }

        @Test
        @DisplayName("cycling past the last Runner comes back to the nearest, not to the first")
        void wrapsToNearest() {
            List<Candidate> roster = List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0));

            assertThat(TrackerCompass.next(roster, Following.of(BEN))).contains(Following.NEAREST);
            assertThat(TrackerCompass.next(roster, null)).contains(Following.of(ANNA));
        }

        @Test
        @DisplayName("cycling from a Runner who is gone starts over at the first")
        void currentGone() {
            assertThat(TrackerCompass.next(List.of(at(ANNA, "hunt", 0, 0)), Following.of(CARO)))
                    .contains(Following.of(ANNA));
        }

        @Test
        @DisplayName("a lone Runner and the nearest are two positions, so the click always does something")
        void singleRunnerStillTogglesTwoWays() {
            List<Candidate> lone = List.of(at(ANNA, "hunt", 0, 0));

            assertThat(TrackerCompass.next(lone, null)).contains(Following.of(ANNA));
            assertThat(TrackerCompass.next(lone, Following.of(ANNA))).contains(Following.NEAREST);
        }

        @Test
        @DisplayName("the nearest is not a Runner of its own")
        void nearestIsNotARunner() {
            assertThat(Following.NEAREST.isNearest()).isTrue();
            assertThat(Following.NEAREST.runner()).isNull();
            assertThat(Following.of(ANNA).isNearest()).isFalse();
        }
    }

    @Nested
    @DisplayName("refresh interval")
    class Refresh {

        @Test
        @DisplayName("the configured interval is used as written when it is sane")
        void asWritten() {
            assertThat(ManhuntSettings.DEFAULTS.withTrackerRefreshTicks(20).trackerRefreshTicksClamped())
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("a nonsensical interval is clamped rather than trusted")
        void clamped() {
            assertThat(ManhuntSettings.DEFAULTS.withTrackerRefreshTicks(0).trackerRefreshTicksClamped())
                    .isEqualTo(1);
            assertThat(ManhuntSettings.DEFAULTS.withTrackerRefreshTicks(9999).trackerRefreshTicksClamped())
                    .isEqualTo(100);
        }
    }
}
