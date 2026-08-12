package de.raindancer.modules.moderation.store;

import de.raindancer.modules.moderation.model.PlayerMiningProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerMiningProfilesTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    @Nested
    @DisplayName("asking about somebody")
    class Asking {

        @Test
        @DisplayName("the first ask makes a fresh profile")
        void makesOneOnFirstAsk(@TempDir Path folder) {
            PlayerMiningProfiles profiles = new PlayerMiningProfiles(folder);

            PlayerMiningProfile profile = profiles.of(ayla);

            assertThat(profile).isNotNull();
            assertThat(profiles.everybody()).containsExactly(ayla);
        }

        @Test
        @DisplayName("the same person always gets the same profile back")
        void isTheSameInstanceEachTime(@TempDir Path folder) {
            PlayerMiningProfiles profiles = new PlayerMiningProfiles(folder);

            assertThat(profiles.of(ayla)).isSameAs(profiles.of(ayla));
        }

        @Test
        @DisplayName("everybody lists only who has actually been asked about")
        void listsOnlyKnownPlayers(@TempDir Path folder) {
            PlayerMiningProfiles profiles = new PlayerMiningProfiles(folder);
            profiles.of(ayla);

            assertThat(profiles.everybody()).containsExactly(ayla).doesNotContain(bram);
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("a profile's numbers survive being written and read back")
        void aRoundTrip(@TempDir Path folder) {
            PlayerMiningProfiles written = new PlayerMiningProfiles(folder);
            written.of(ayla).recordBlock(true, 1000L);
            written.of(ayla).recordApproach(80, 1000L);

            assertThat(written.flush()).isTrue();

            PlayerMiningProfiles read = new PlayerMiningProfiles(folder);
            read.load();

            PlayerMiningProfile restored = read.of(ayla);
            assertThat(restored.oreRatio()).isEqualTo(written.of(ayla).oreRatio());
            assertThat(restored.approachDirectness()).isEqualTo(written.of(ayla).approachDirectness());
            assertThat(restored.observedOre()).isEqualTo(1);
        }

        @Test
        @DisplayName("loading replaces whatever was already held, rather than adding to it")
        void loadReplaces(@TempDir Path folder) {
            PlayerMiningProfiles profiles = new PlayerMiningProfiles(folder);
            profiles.of(ayla);
            profiles.flush();

            profiles.of(bram);
            profiles.load();

            assertThat(profiles.everybody())
                    .as("bram was never written to disk, so loading must forget about them")
                    .containsExactly(ayla);
        }

        @Test
        @DisplayName("an entry that is not a player id is skipped and logged, not thrown")
        void unreadableEntryIsSkipped(@TempDir Path folder) throws Exception {
            Files.writeString(folder.resolve("xray-suspicion.yml"),
                    "players:\n  not-a-uuid:\n    ore-ratio: 0.5\n");

            PlayerMiningProfiles profiles = new PlayerMiningProfiles(folder);
            profiles.load();

            assertThat(profiles.everybody()).isEmpty();
        }

        @Test
        @DisplayName("nothing on disk yet is an empty registry, not an error")
        void nothingOnDiskYet(@TempDir Path folder) {
            PlayerMiningProfiles profiles = new PlayerMiningProfiles(folder);

            profiles.load();

            assertThat(profiles.everybody()).isEmpty();
        }
    }
}
