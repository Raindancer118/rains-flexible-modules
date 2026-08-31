package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.CrossWorldTracking;
import de.raindancer.modules.manhunt.ManhuntSettings.TrackerTargets;
import de.raindancer.modules.manhunt.service.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.service.TrackerCompass.Candidate;
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
        @DisplayName("CHOSEN keeps the picked Runner even when somebody else is nearer")
        void chosenIsSticky() {
            Aim aim = compass().aim(HUNTER,
                    List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), ANNA);

            assertThat(aim.target()).isEqualTo(ANNA);
        }

        @Test
        @DisplayName("CHOSEN falls back to the nearest when the picked Runner is gone")
        void chosenGoneFallsBack() {
            Aim aim = compass().aim(HUNTER,
                    List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), CARO);

            assertThat(aim.target()).isEqualTo(BEN);
        }

        @Test
        @DisplayName("NEAREST ignores a pick entirely — the needle always swings to the closest")
        void nearestIgnoresThePick() {
            TrackerCompass nearest = compass(
                    ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.NEAREST),
                    new PortalMemory());

            Aim aim = nearest.aim(HUNTER, List.of(at(ANNA, "hunt", 300, 0), at(BEN, "hunt", 40, 30)), ANNA);

            assertThat(aim.target()).isEqualTo(BEN);
        }

        @Test
        @DisplayName("a Hunter may only pick a target when the mode says so")
        void pickingIsGated() {
            assertThat(compass().allowsPicking()).isTrue();
            assertThat(compass(ManhuntSettings.DEFAULTS.withTrackerTargets(TrackerTargets.NEAREST),
                    new PortalMemory()).allowsPicking()).isFalse();
        }
    }

    @Nested
    @DisplayName("a Runner in another dimension")
    class AcrossDimensions {

        private static final Point PORTAL = new Point("hunt", 250, 70, -80);

        private static PortalMemory memoryWithAnnasPortal() {
            PortalMemory memory = new PortalMemory();
            memory.remember(ANNA, PORTAL);
            return memory;
        }

        @Test
        @DisplayName("LAST_PORTAL points at the portal the Runner went through")
        void pointsAtThePortal() {
            Aim aim = compass(ManhuntSettings.DEFAULTS, memoryWithAnnasPortal())
                    .aim(HUNTER, List.of(at(ANNA, "hunt_nether", 12, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.PORTAL);
            assertThat(aim.target()).isEqualTo(ANNA);
            assertThat(aim.at()).isEqualTo(PORTAL);
            assertThat(aim.worldName()).isEqualTo("hunt_nether");
            assertThat(aim.distance()).isEqualTo(HUNTER.distanceTo(PORTAL).blocks());
        }

        @Test
        @DisplayName("the newest crossing is the one pointed at")
        void newestCrossingWins() {
            PortalMemory memory = new PortalMemory();
            memory.remember(ANNA, new Point("hunt", 10, 64, 0));
            memory.remember(ANNA, new Point("hunt", 900, 64, 0));

            Aim aim = compass(ManhuntSettings.DEFAULTS, memory)
                    .aim(HUNTER, List.of(at(ANNA, "hunt_nether", 12, 0)), null);

            assertThat(aim.at()).isEqualTo(new Point("hunt", 900, 64, 0));
        }

        @Test
        @DisplayName("LAST_PORTAL names the dimension when no crossing was ever seen")
        void fallsBackToNaming() {
            Aim aim = compass(ManhuntSettings.DEFAULTS, new PortalMemory())
                    .aim(HUNTER, List.of(at(ANNA, "hunt_nether", 12, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.OTHER_WORLD);
            assertThat(aim.worldName()).isEqualTo("hunt_nether");
        }

        @Test
        @DisplayName("a crossing in a third world is no help to a Hunter in this one")
        void crossingInAnotherWorldIsNoHelp() {
            PortalMemory memory = new PortalMemory();
            memory.remember(ANNA, new Point("hunt_nether", 5, 64, 5));

            Aim aim = compass(ManhuntSettings.DEFAULTS, memory)
                    .aim(HUNTER, List.of(at(ANNA, "hunt_the_end", 0, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.OTHER_WORLD);
        }

        @Test
        @DisplayName("NAME_WORLD names the dimension even with a crossing on record")
        void namingOnly() {
            Aim aim = compass(ManhuntSettings.DEFAULTS.withTrackerCrossWorld(CrossWorldTracking.NAME_WORLD),
                    memoryWithAnnasPortal()).aim(HUNTER, List.of(at(ANNA, "hunt_nether", 12, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.OTHER_WORLD);
            assertThat(aim.worldName()).isEqualTo("hunt_nether");
        }

        @Test
        @DisplayName("HIDDEN says nothing at all, crossing or no crossing")
        void hidden() {
            Aim aim = compass(ManhuntSettings.DEFAULTS.withTrackerCrossWorld(CrossWorldTracking.HIDDEN),
                    memoryWithAnnasPortal()).aim(HUNTER, List.of(at(ANNA, "hunt_nether", 12, 0)), null);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.NONE);
        }

        @Test
        @DisplayName("a picked Runner in another dimension is still the one followed")
        void pickedAcrossDimensions() {
            Aim aim = compass(ManhuntSettings.DEFAULTS, memoryWithAnnasPortal())
                    .aim(HUNTER, List.of(at(ANNA, "hunt_the_end", 0, 0), at(BEN, "hunt", 10, 0)), ANNA);

            assertThat(aim.kind()).isEqualTo(Aim.Kind.PORTAL);
            assertThat(aim.target()).isEqualTo(ANNA);
        }

        @Test
        @DisplayName("with everybody gone below, the nearest known portal is the one chosen")
        void nearestPortalWhenAllAreAway() {
            PortalMemory memory = new PortalMemory();
            memory.remember(ANNA, new Point("hunt", 800, 64, 0));
            memory.remember(BEN, new Point("hunt", 60, 64, 0));

            Aim aim = compass(ManhuntSettings.DEFAULTS, memory)
                    .aim(HUNTER, List.of(at(ANNA, "hunt_nether", 0, 0), at(BEN, "hunt_nether", 0, 0)), null);

            assertThat(aim.target()).isEqualTo(BEN);
            assertThat(aim.kind()).isEqualTo(Aim.Kind.PORTAL);
        }
    }

    @Nested
    @DisplayName("next")
    class Cycling {

        @Test
        @DisplayName("nobody to cycle to with no Runners left")
        void nothingToCycleTo() {
            assertThat(TrackerCompass.next(List.of(), ANNA)).isEmpty();
        }

        @Test
        @DisplayName("cycling from nobody lands on the first Runner")
        void fromNobody() {
            assertThat(TrackerCompass.next(List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0)), null))
                    .contains(ANNA);
        }

        @Test
        @DisplayName("cycling steps to the next Runner in the roster")
        void stepsOn() {
            List<Candidate> roster = List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0),
                    at(CARO, "hunt", 0, 0));

            assertThat(TrackerCompass.next(roster, ANNA)).contains(BEN);
            assertThat(TrackerCompass.next(roster, BEN)).contains(CARO);
        }

        @Test
        @DisplayName("cycling past the last Runner wraps to the first")
        void wraps() {
            List<Candidate> roster = List.of(at(ANNA, "hunt", 0, 0), at(BEN, "hunt", 0, 0));

            assertThat(TrackerCompass.next(roster, BEN)).contains(ANNA);
        }

        @Test
        @DisplayName("cycling from a Runner who is gone starts over at the first")
        void currentGone() {
            assertThat(TrackerCompass.next(List.of(at(ANNA, "hunt", 0, 0)), CARO)).contains(ANNA);
        }

        @Test
        @DisplayName("a lone Runner cycles to themselves rather than to nobody")
        void singleWrapsToItself() {
            assertThat(TrackerCompass.next(List.of(at(ANNA, "hunt", 0, 0)), ANNA)).contains(ANNA);
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
