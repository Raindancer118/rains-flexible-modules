package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three more instances of this project's own recurring failure mode: a service built, unit tested, and
 * never actually reached from a running server. A source scan, the same shape as
 * {@code TheTeamAdminPageIsActuallyReachableTest} and {@code TheHttpApiActuallyStartsTest}, for the same
 * reason — a method that works in a test proves the method works, not that anything on a real server ever
 * calls it.
 */
class EveryServiceIsActuallyWiredTest {

    private static String wiringSource() {
        try {
            return Files.readString(Path.of(
                    "src/main/java/de/raindancer/modules/hungergames/HungerGamesWiring.java"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read HungerGamesWiring.java", unreadable);
        }
    }

    @Test
    @DisplayName("the scan reads the real wiring, so it cannot pass by reading nothing")
    void theScanIsNotVacuous() {
        assertThat(wiringSource()).contains("private void tick()");
    }

    @Nested
    @DisplayName("a sponsor beacon opens the shop")
    class SponsorBeacons {

        // The bug this was written for: SponsorBeaconService placed a real Material.BEACON block and had
        // isSponsorBeacon(Location) ready for a listener to call — its own class javadoc said so — but no
        // listener was ever written. Right-clicking a sponsor beacon opened vanilla's own beacon interface,
        // a pyramid-and-power-selection screen with no shop in it at all.

        @Test
        @DisplayName("SponsorBeaconListener is registered")
        void registered() {
            assertThat(wiringSource())
                    .as("built and unit tested is not the same as a tribute ever seeing the shop when they "
                            + "click a beacon — nothing before this registered SponsorBeaconListener")
                    .contains("new de.raindancer.modules.hungergames.listener.SponsorBeaconListener(");
        }
    }

    @Nested
    @DisplayName("monster waves actually spawn something")
    class MonsterWaves {

        // The bug this was written for: MonsterWaveMenu queued a wave, MonsterWaveService.start built it
        // correctly, and MonsterWaveService.tick(Duration) — the only method that reads the queue and
        // places mobs — was never called from anywhere in the module. A gamemaster starting a wave saw the
        // "running series" counter go up and never saw a single mob.

        @Test
        @DisplayName("HungerGamesWiring.tick() calls monsterWaves.tick(...)")
        void ticked() {
            assertThat(wiringSource())
                    .as("a queued wave that is never ticked is a wave that never spawns anything, and "
                            + "the only symptom is a running-series counter with nothing behind it")
                    .contains("monsterWaves.tick(");
        }
    }

    @Nested
    @DisplayName("Hermes' Boots keep their material across a restart")
    class HermesBootsMaterial {

        // The bug this was written for, one layer down from a wiring gap: CustomItems.defineIfAbsent only
        // ever patches the ability field of an existing definition — never the material, name or lore — so
        // a material changed in code never reaches a server whose items.yml already has a persisted entry
        // for that key. The Hermes' Boots redesign (worn, not clicked — see HermesBootsService's own class
        // note) changed the material to GOLDEN_BOOTS in code; a server that had already booted the old
        // FEATHER-based version kept handing out a feather forever, silently, because defineIfAbsent saw
        // the key was already present and left the stale material exactly as it was.
        //
        // Nothing here can prove a live server's items.yml is clean — that is a deploy-time fact, not a
        // compile-time one — so this only pins the source of truth: the material the code actually asks
        // for, so the next person changing it has one place a diff shows up.

        @Test
        @DisplayName("HermesBootsService registers GOLDEN_BOOTS")
        void registersGoldenBoots() throws IOException {
            String source = Files.readString(Path.of(
                    "src/main/java/de/raindancer/modules/hungergames/service/HermesBootsService.java"));
            assertThat(source).contains(".material(Material.GOLDEN_BOOTS)");
        }
    }
}
