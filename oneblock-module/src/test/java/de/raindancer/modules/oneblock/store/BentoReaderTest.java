package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.BentoIsland;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Against files shaped exactly like S15's BentoBox 3.23.3 / AOneBlock 1.28.0 database. */
class BentoReaderTest {

    private static Path folder() throws URISyntaxException {
        return Path.of(BentoReaderTest.class.getResource("/bentobox").toURI());
    }

    @Test
    @DisplayName("owned islands that were not deleted are read; the rest are said and left")
    void readsOwnedIslands() throws Exception {
        BentoReader.Result result = BentoReader.read(folder().resolve("Island"), folder().resolve("OneBlockIslands"));

        assertThat(result.islands()).extracting(BentoIsland::bentoId)
                .containsExactlyInAnyOrder("AOneBlock18be87da-ae72-4295-afb7-3d9cd6da83d7", "AOneBlocknocount");
        assertThat(result.skipped()).hasSize(2).anySatisfy(line -> assertThat(line).contains("AOneBlockunowned"))
                .anySatisfy(line -> assertThat(line).contains("AOneBlockdeleted"));
    }

    @Test
    @DisplayName("an island reads with its owner, its team from member rank up, the block at its centre and its count")
    void anIsland() throws Exception {
        BentoIsland island = BentoReader.read(folder().resolve("Island"), folder().resolve("OneBlockIslands")).islands()
                .stream().filter(it -> it.bentoId().startsWith("AOneBlock18be")).findFirst().orElseThrow();

        assertThat(island.owner()).isEqualTo(UUID.fromString("3673c488-98af-41b2-9b72-6894f42640de"));
        assertThat(island.members()).containsExactlyInAnyOrder(
                UUID.fromString("a1ecd8ef-6e59-4a4a-b99f-a85fe1082cdd"),
                UUID.fromString("46af6aa4-4e65-414c-9c11-6323ea55aaf2"));
        assertThat(island.x()).isEqualTo(-800);
        assertThat(island.y()).isEqualTo(80);
        assertThat(island.z()).isEqualTo(800);
        assertThat(island.protection()).isEqualTo(50);
        assertThat(island.range()).isEqualTo(400);
        assertThat(island.lifetime()).isEqualTo(8100);
        assertThat(island.spawn()).hasValueSatisfying(spawn -> {
            assertThat(spawn.x()).isEqualTo(-797.5);
            assertThat(spawn.yaw()).isEqualTo(90f);
        });
        assertThat(island.created()).isEqualTo(1791993424878L);
    }

    @Test
    @DisplayName("an island AOneBlock never counted has a count of zero, and its largest protection is used")
    void noCount() throws Exception {
        BentoIsland island = BentoReader.read(folder().resolve("Island"), folder().resolve("OneBlockIslands")).islands()
                .stream().filter(it -> it.bentoId().equals("AOneBlocknocount")).findFirst().orElseThrow();

        assertThat(island.lifetime()).isZero();
        assertThat(island.protection()).isEqualTo(64);
        assertThat(island.spawn()).isEmpty();
    }

    @Test
    @DisplayName("where the nether and the end start, from AOneBlock's phase file names")
    void phases() throws Exception {
        BentoReader.Phases phases = BentoReader.phases(folder().resolve("phases"));

        assertThat(phases.nether()).isEqualTo(7500);
        assertThat(phases.end()).isEqualTo(11500);
    }

    @Test
    @DisplayName("without phase files nothing is unlocked by count")
    void noPhases() {
        BentoReader.Phases phases = BentoReader.phases(Path.of("/nowhere/at/all"));

        assertThat(phases.nether()).isEqualTo(Long.MAX_VALUE);
        assertThat(phases.end()).isEqualTo(Long.MAX_VALUE);
        assertThat(List.of(phases.nether())).isNotEmpty();
    }
}
