package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the HTTP admin API is actually constructed and started, not merely built and tested.
 *
 * <h2>The bug this was written for</h2>
 * {@code HttpApiService} and all seven of its endpoint groups — {@code StatusEndpoints},
 * {@code TeamEndpoints}, {@code GameEndpoints}, {@code EventEndpoints}, {@code ConfigEndpoints},
 * {@code LootEndpoints}, {@code AdminEndpoints} — were written, and every one of them unit tested. Nothing
 * anywhere in {@code HungerGamesWiring} or {@code HungerGamesModule} ever called {@code new
 * HttpApiService(...)}. The settings page showed a bind address and a port, and the boot banner printed
 * them, both correctly, for a socket that had never opened. This is the fourth time in this port that
 * finished, tested code was simply never called — the session store, the whole wiring class, the four item
 * services, and now this — and every time the only symptom on a clean boot was a line that was
 * <em>absent</em>.
 *
 * <p>A source scan rather than constructing the class directly, for the same reason
 * {@code EveryItemIsRegisteredTest} reads source instead of building a {@code HungerGamesWiring}: what is
 * being proven is that the real wiring class — the one actually loaded by a real server — contains the
 * call, not that some other object could be made to contain it.
 */
class TheHttpApiActuallyStartsTest {

    private static final Path WIRING = Path.of(
            "src/main/java/de/raindancer/modules/hungergames/HungerGamesWiring.java");

    private static String wiring() {
        try {
            return Files.readString(WIRING);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + WIRING, unreadable);
        }
    }

    @Test
    @DisplayName("the scan reads the real wiring class, so it cannot pass by reading nothing")
    void theScanIsNotVacuous() {
        assertThat(wiring()).contains("HungerGamesServices start()");
    }

    @Test
    @DisplayName("HttpApiService is actually constructed")
    void constructed() {
        assertThat(wiring())
                .as("every endpoint being written and tested is not the same thing as the socket ever "
                        + "opening — nothing before this called new HttpApiService(...) at all")
                .contains("new de.raindancer.modules.hungergames.service.HttpApiService(");
    }

    @Test
    @DisplayName("it is actually started")
    void started() {
        assertThat(wiring()).contains("httpApi.start()");
    }

    @Test
    @DisplayName("it is actually stopped when the module stops")
    void stopped() {
        assertThat(wiring())
                .as("a socket opened and never closed outlives the plugin that opened it")
                .contains("httpApi::stop");
    }

    @Test
    @DisplayName("a generated key is actually written back to the config, not only logged")
    void keyPersisted() {
        assertThat(wiring())
                .as("HttpApiService.ensureThereIsAKey calls rememberKey with a fresh key precisely so it "
                        + "reaches config.yml — a rememberKey that goes nowhere means every restart hands "
                        + "out a different key and nothing that used the old one can reach the API again")
                .contains("settingsStore.set(\"api.key\"");
    }

    @Test
    @DisplayName("settings reaching the API are guarded against running before start()")
    void settingsGuarded() {
        // applySettingsNow() runs before start() (see HungerGamesModule), so httpApi and apiSupport are
        // still null the first time settingsChanged fires. An unguarded call here is a NullPointerException
        // on every single boot, not a rare race.
        assertThat(wiring()).contains("if (httpApi != null)").contains("if (apiSupport != null)");
    }
}
