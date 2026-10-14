package de.raindancer.modules.tpa.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.tpa.model.TpaPrefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class TpaPrefsOnANetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private TpaPrefsFile server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        Documents documents = new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
        TpaPrefsFile file = new TpaPrefsFile(new YamlStore(plugins.resolve("RainsTpa").resolve("tpa.yml"),
                documents, Documents.Scope.NETWORK));
        file.load();
        return file;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a block set on one server holds on the other, and lifting it does too")
    void blocksFollowThePlayer() {
        TpaPrefsFile smp = server("smp");
        TpaPrefsFile arena = server("arena");

        smp.set(ALEX, "Alex", TpaPrefs.untouched().blocking(STEVE));
        hub.drain();
        assertThat(arena.of(ALEX).hasBlocked(STEVE)).isTrue();

        arena.set(ALEX, "Alex", arena.of(ALEX).unblocking(STEVE));
        hub.drain();
        assertThat(smp.of(ALEX).hasBlocked(STEVE)).isFalse();
    }

    @Test
    @DisplayName("two servers changing two different players' choices at once keep both")
    void differentPlayersDoNotClash() {
        TpaPrefsFile smp = server("smp");
        TpaPrefsFile arena = server("arena");

        smp.set(ALEX, "Alex", TpaPrefs.untouched().refusingEverybody());
        arena.set(STEVE, "Steve", TpaPrefs.untouched().autoAccepting(true));
        hub.drain();

        assertThat(smp.of(STEVE).autoAccept()).isTrue();
        assertThat(arena.of(ALEX).accepting()).isFalse();
    }
}
