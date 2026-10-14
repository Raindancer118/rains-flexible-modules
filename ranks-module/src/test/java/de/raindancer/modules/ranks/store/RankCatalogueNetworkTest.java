package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.ranks.RanksModule;
import de.raindancer.modules.ranks.model.Rank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The ranks an owner and staff make are the ranks of every server. */
@Tag("database")
class RankCatalogueNetworkTest {

    @TempDir
    Path plugins;

    private Servers network;

    private RankCatalogue on(String id) {
        if (network == null) {
            network = new Servers(plugins);
        }
        Documents documents = network.server(id);
        RankCatalogue catalogue = new RankCatalogue(network.network(documents, "ranks.yml"),
                () -> RanksModule.class.getResourceAsStream("ranks.yml"), network.network(documents, "custom-ranks.yml"));
        catalogue.reload();
        catalogue.watch();
        return catalogue;
    }

    @AfterEach
    void close() {
        if (network != null) {
            network.close();
        }
    }

    private static Rank vip() {
        return new Rank("vip", "VIP", "<gold>[VIP]", 5, List.of(), false);
    }

    @Test
    @DisplayName("a rank made on one server is there on the other")
    void madeRankTravels() {
        RankCatalogue smp = on("smp");
        RankCatalogue arena = on("arena");

        assertThat(smp.define(vip())).isTrue();
        network.heard();

        assertThat(arena.find("vip")).isPresent();
    }

    @Test
    @DisplayName("a rank deleted on one server is gone from the other, a shipped one too")
    void deletionTravels() {
        RankCatalogue smp = on("smp");
        RankCatalogue arena = on("arena");
        assertThat(arena.find("dev")).isPresent();

        smp.remove("dev");
        network.heard();

        assertThat(arena.find("dev")).isEmpty();
    }

    @Test
    @DisplayName("two ranks made at once on two servers are both kept")
    void bothKept() {
        RankCatalogue smp = on("smp");
        RankCatalogue arena = on("arena");

        smp.define(vip());
        arena.define(new Rank("fan", "Fan", "<red>[FAN]", 3, List.of(), false));
        network.heard();

        assertThat(smp.find("fan")).isPresent();
        assertThat(arena.find("vip")).isPresent();
    }

    @Test
    @DisplayName("what the owner writes into the shipped ranks is the network's, and the shipped ones are written once")
    void shippedRanksAreWrittenOnce() {
        RankCatalogue smp = on("smp");
        smp.define(vip());
        YamlStore ranks = network.network(network.server("late"), "ranks.yml");
        String before = ranks.readText().orElseThrow();

        on("arena");

        assertThat(ranks.readText().orElseThrow()).isEqualTo(before);
        assertThat(smp.byId()).containsKeys("owner", "dev", "vip");
    }

    @Test
    @DisplayName("documents that cannot be read leave the ranks held alone")
    void unreadableKeepsRanks() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        RankCatalogue catalogue = new RankCatalogue(
                new YamlStore(plugins.resolve("RainsRanks").resolve("ranks.yml"), documents, Documents.Scope.NETWORK),
                () -> RanksModule.class.getResourceAsStream("ranks.yml"),
                new YamlStore(plugins.resolve("RainsRanks").resolve("custom-ranks.yml"), documents, Documents.Scope.NETWORK));
        int before = catalogue.reload();
        assertThat(before).isGreaterThanOrEqualTo(3);

        database.close();

        assertThat(catalogue.reload()).isEqualTo(before);
        assertThat(catalogue.find("owner")).isPresent();
        assertThat(catalogue.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("1.x files of a server are brought into the network's documents on first use")
    void oneXFilesAreImported() throws Exception {
        network = new Servers(plugins);
        Path folder = Files.createDirectories(plugins.resolve("RainsRanks"));
        Files.writeString(folder.resolve("ranks.yml"), "ranks:\n  boss:\n    tag: \"<red>[BOSS]\"\n    weight: 90\n");
        Files.writeString(folder.resolve("custom-ranks.yml"), "ranks:\n  guest:\n    tag: \"[G]\"\n");
        Documents documents = network.server("smp");
        RankCatalogue catalogue = new RankCatalogue(network.network(documents, "ranks.yml"),
                () -> RanksModule.class.getResourceAsStream("ranks.yml"), network.network(documents, "custom-ranks.yml"));

        catalogue.reload();

        assertThat(catalogue.byId()).containsKeys("boss", "guest");
        assertThat(folder.resolve("ranks.yml")).doesNotExist();
    }
}
