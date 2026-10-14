package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class BaitSeedTest {

    @TempDir
    Path folder;

    private final List<Database> databases = new ArrayList<>();

    private Secrets server(String ignored) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new Secrets(database, System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("the seed is made once and kept, so the bait layer stays put across restarts")
    void kept() {
        long first = BaitSeed.load(server("smp"), folder.resolve("smp"));
        assertThat(BaitSeed.load(server("smp"), folder.resolve("smp"))).isEqualTo(first);
    }

    @Test
    @DisplayName("every server of the network lays its baits from the same seed")
    void oneSecretForTheNetwork() {
        long smp = BaitSeed.load(server("smp"), folder.resolve("smp"));
        long arena = BaitSeed.load(server("arena"), folder.resolve("arena"));
        assertThat(arena).isEqualTo(smp);
    }

    @Test
    @DisplayName("a 1.x seed file is the network's seed, unchanged, and is kept renamed")
    void importedFromTheOldFile() throws Exception {
        Path plugin = Files.createDirectories(folder.resolve("smp"));
        Files.writeString(plugin.resolve(BaitSeed.FILE), "-4879283749823749823");

        long seed = BaitSeed.load(server("smp"), plugin);

        assertThat(seed).isEqualTo(-4879283749823749823L);
        assertThat(plugin.resolve(BaitSeed.FILE)).doesNotExist();
        assertThat(plugin.resolve(BaitSeed.FILE + ".imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("without the database a seed is still drawn rather than the module failing to start")
    void withoutTheDatabase() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
        databases.add(broken);

        BaitSeed.load(new Secrets(broken, System::currentTimeMillis), folder);
    }
}
