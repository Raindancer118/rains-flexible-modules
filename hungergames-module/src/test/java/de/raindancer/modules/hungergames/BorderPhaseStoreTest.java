package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.store.BorderPhaseStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BorderPhaseStore}: the compact phase syntax (ported from {@code BorderPhaseParserTest}), and the
 * file's own invariants — a missing file is no phases, a corrupt one refuses to be written over.
 */
class BorderPhaseStoreTest {

    private static final Duration GAME = Duration.ofMinutes(180);

    @Nested
    @DisplayName("the compact phase syntax")
    class Syntax {

        @Test
        @DisplayName("a time trigger with duration mode")
        void parsesDurationMode() {
            BorderPhaseConfig phase = BorderPhaseStore.parse("20m -> 1000 @ duration:10m", GAME);

            assertThat(phase.trigger().time()).contains(Duration.ofMinutes(20));
            assertThat(phase.targetSize()).isEqualTo(1000, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(phase.mode()).isEqualTo(BorderPhaseConfig.Mode.DURATION);
            assertThat(phase.duration()).contains(Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("an alive-count trigger with fixed speed")
        void parsesAliveTriggerAndSpeed() {
            BorderPhaseConfig phase = BorderPhaseStore.parse("alive<4 -> 200 @ speed:1.0", GAME);

            assertThat(phase.trigger().aliveBelow()).contains(4);
            assertThat(phase.trigger().time()).isEmpty();
            assertThat(phase.mode()).isEqualTo(BorderPhaseConfig.Mode.FIXED_SPEED);
            assertThat(phase.edgeSpeed()).contains(1.0);
        }

        @Test
        @DisplayName("a percentage trigger resolves against the game duration")
        void parsesPercentTrigger() {
            BorderPhaseConfig phase = BorderPhaseStore.parse("50% -> 1000 @ max:2.5", GAME);

            assertThat(phase.trigger().time()).contains(Duration.ofMinutes(90));
            assertThat(phase.mode()).isEqualTo(BorderPhaseConfig.Mode.MAX_SPEED);
        }

        @Test
        @DisplayName("an OR-combination of time and alive count")
        void parsesCombinedTrigger() {
            BorderPhaseConfig phase = BorderPhaseStore.parse("1h|alive<6 -> 500 @ max:2.5", GAME);

            assertThat(phase.trigger().time()).contains(Duration.ofHours(1));
            assertThat(phase.trigger().aliveBelow()).contains(6);
        }

        @Test
        @DisplayName("a syntax error is rejected with a readable message")
        void rejectsInvalidSyntax() {
            assertThat(catchThrowable(() -> BorderPhaseStore.parse("broken", GAME)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> BorderPhaseStore.parse("20m -> 1000 @ warp:9", GAME)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(BorderPhaseStore.validateList(List.of("20m -> 1000 @ duration:ten"))).isPresent();
            assertThat(BorderPhaseStore.validateList(
                    List.of("20m -> 1000 @ duration:10m", "alive<4 -> 200 @ speed:1.0"))).isEmpty();
        }

        @Test
        @DisplayName("'prefer:' sets the time anchor in max mode, and is rejected everywhere else")
        void parsesPreferAnchor() {
            BorderPhaseConfig phase = BorderPhaseStore.parse("50% -> 1000 @ max:2.5,prefer:25m", GAME);

            assertThat(phase.mode()).isEqualTo(BorderPhaseConfig.Mode.MAX_SPEED);
            assertThat(phase.edgeSpeed()).contains(2.5);
            assertThat(phase.duration()).contains(Duration.ofMinutes(25));

            assertThat(catchThrowable(() -> BorderPhaseStore.parse("50% -> 1000 @ speed:1.0,prefer:25m", GAME)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("serialising and re-parsing keeps every phase")
        void serializerRoundtrip() {
            List<String> original = List.of(
                    "20m -> 1000 @ duration:10m",
                    "1h|alive<4 -> 200 @ speed:1.0",
                    "90m -> 150 @ max:2.5",
                    "95m -> 120 @ max:2.5,prefer:20m");
            List<BorderPhaseConfig> phases = original.stream().map(line -> BorderPhaseStore.parse(line, GAME)).toList();

            List<String> serialized = BorderPhaseStore.serialize(phases);
            List<BorderPhaseConfig> reparsed = serialized.stream().map(line -> BorderPhaseStore.parse(line, GAME)).toList();

            assertThat(reparsed).isEqualTo(phases);
        }

        private Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
            return org.assertj.core.api.Assertions.catchThrowable(callable);
        }
    }

    @Nested
    @DisplayName("the file")
    class TheFile {

        @Test
        @DisplayName("a full phase list survives a save and a load")
        void roundTrip(@TempDir Path dir) {
            BorderPhaseStore store = new BorderPhaseStore(dir.resolve("border-phases.yml"), GAME);
            List<BorderPhaseConfig> phases = List.of(
                    BorderPhaseStore.parse("20m -> 1000 @ duration:10m", GAME),
                    BorderPhaseStore.parse("alive<4 -> 200 @ speed:1.5", GAME));

            assertThat(store.save(phases)).isTrue();

            assertThat(store.load()).isEqualTo(phases);
            assertThat(store.problems()).isEmpty();
        }

        @Test
        @DisplayName("no file yet is zero phases, not an exception")
        void missingFile(@TempDir Path dir) {
            BorderPhaseStore store = new BorderPhaseStore(dir.resolve("border-phases.yml"), GAME);

            assertThat(store.load()).isEmpty();
            assertThat(store.problems()).isEmpty();
        }

        @Test
        @DisplayName("a corrupt file is reported, quarantined, and never overwritten in place")
        void corruptYaml(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("border-phases.yml");
            Files.writeString(file, "phases: [broken: [[[");
            BorderPhaseStore store = new BorderPhaseStore(file, GAME);

            assertThat(store.load()).isEmpty();
            assertThat(store.problems()).isNotEmpty();
            assertThat(Files.exists(file)).isFalse();
        }

        @Test
        @DisplayName("one bad line rejects the whole list rather than silently shrinking the border's shape")
        void oneBadLineRejectsTheWholeFile(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("border-phases.yml");
            Files.writeString(file, """
                    phases:
                      - "20m -> 1000 @ duration:10m"
                      - "this is not a phase"
                    """);
            BorderPhaseStore store = new BorderPhaseStore(file, GAME);

            assertThat(store.load()).isEmpty();
            assertThat(store.problems()).isNotEmpty();
            // the file itself is untouched — an admin can still open and fix it by hand
            assertThat(Files.exists(file)).isTrue();
        }

        @Test
        @DisplayName("saving an empty list clears the phases rather than leaving stale ones behind")
        void savingEmptyListClears(@TempDir Path dir) {
            BorderPhaseStore store = new BorderPhaseStore(dir.resolve("border-phases.yml"), GAME);
            store.save(List.of(BorderPhaseStore.parse("20m -> 1000 @ duration:10m", GAME)));

            assertThat(store.save(List.of())).isTrue();

            assertThat(store.load()).isEmpty();
        }
    }
}
