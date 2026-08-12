package de.raindancer.modules.moderation.store;

import de.raindancer.modules.moderation.model.ApproachReading;
import de.raindancer.modules.moderation.model.MinedBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedFindingsTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    private static ApproachReading reading(int x, int directness) {
        return new ApproachReading(new MinedBlock("world", x, 64, 0, "DIAMOND_ORE"), 10, 8.0,
                directness);
    }

    @Nested
    @DisplayName("remembering")
    class Remembering {

        @Test
        @DisplayName("the first finding makes an entry")
        void addsAFinding(@TempDir Path folder) {
            PersistedFindings findings = new PersistedFindings(folder);

            findings.add(ayla, reading(0, 90));

            assertThat(findings.of(ayla)).hasSize(1);
            assertThat(findings.everybody()).containsExactly(ayla);
        }

        @Test
        @DisplayName("one person's findings are not another's")
        void keepsPeopleApart(@TempDir Path folder) {
            PersistedFindings findings = new PersistedFindings(folder);
            findings.add(ayla, reading(0, 90));

            assertThat(findings.of(bram)).isEmpty();
        }

        @Test
        @DisplayName("a null player or a null reading changes nothing")
        void nullsAreHarmless(@TempDir Path folder) {
            PersistedFindings findings = new PersistedFindings(folder);

            findings.add(null, reading(0, 90));
            findings.add(ayla, null);

            assertThat(findings.everybody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("holding only so much")
    class Capacity {

        @Test
        @DisplayName("the oldest finding is dropped once a player's list is full")
        void dropsTheOldest(@TempDir Path folder) {
            PersistedFindings findings = new PersistedFindings(folder);
            for (int i = 0; i < PersistedFindings.CAPACITY_PER_PLAYER + 5; i++) {
                findings.add(ayla, reading(i, 50));
            }

            assertThat(findings.of(ayla)).hasSize(PersistedFindings.CAPACITY_PER_PLAYER);
            assertThat(findings.of(ayla).getFirst().ore().x())
                    .as("the first five, being oldest, should have rolled off")
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("a finding's every field survives being written and read back")
        void aRoundTrip(@TempDir Path folder) {
            PersistedFindings written = new PersistedFindings(folder);
            ApproachReading original = reading(42, 77);
            written.add(ayla, original);

            assertThat(written.flush()).isTrue();

            PersistedFindings read = new PersistedFindings(folder);
            read.load();

            ApproachReading restored = read.of(ayla).getFirst();
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("loading replaces whatever was already held, rather than adding to it")
        void loadReplaces(@TempDir Path folder) {
            PersistedFindings findings = new PersistedFindings(folder);
            findings.add(ayla, reading(0, 50));
            findings.flush();

            findings.add(bram, reading(0, 50));
            findings.load();

            assertThat(findings.everybody())
                    .as("bram was never written to disk, so loading must forget about them")
                    .containsExactly(ayla);
        }

        @Test
        @DisplayName("an entry that is not a player id is skipped and logged, not thrown")
        void unreadableEntryIsSkipped(@TempDir Path folder) throws Exception {
            Files.writeString(folder.resolve("xray-findings.yml"),
                    "players:\n  not-a-uuid:\n  - world: world\n    x: 0\n    y: 64\n    z: 0\n"
                            + "    material: DIAMOND_ORE\n    path-length: 1\n    distance: 1.0\n"
                            + "    directness: 100\n");

            PersistedFindings findings = new PersistedFindings(folder);
            findings.load();

            assertThat(findings.everybody()).isEmpty();
        }

        @Test
        @DisplayName("a malformed row for a real player is skipped, not thrown")
        void malformedRowIsSkipped(@TempDir Path folder) throws Exception {
            Files.writeString(folder.resolve("xray-findings.yml"),
                    "players:\n  " + ayla + ":\n  - world: world\n    x: not-a-number\n");

            PersistedFindings findings = new PersistedFindings(folder);
            findings.load();

            assertThat(findings.of(ayla)).isEmpty();
        }

        @Test
        @DisplayName("nothing on disk yet is an empty registry, not an error")
        void nothingOnDiskYet(@TempDir Path folder) {
            PersistedFindings findings = new PersistedFindings(folder);

            findings.load();

            assertThat(findings.everybody()).isEmpty();
        }
    }
}
