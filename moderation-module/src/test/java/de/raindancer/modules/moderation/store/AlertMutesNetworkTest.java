package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.moderation.model.CheckType;
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

/** A moderator's mutes follow them from server to server; two servers muting different things keep both. */
@Tag("database")
class AlertMutesNetworkTest {

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final UUID mod = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private AlertMutes server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        Documents documents = new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
        AlertMutes mutes = new AlertMutes(new YamlStore(plugins.resolve("RainsAntiCheat").resolve("alert-mutes.yml"),
                documents, Documents.Scope.NETWORK));
        mutes.load();
        open.add(mutes.connect());
        return mutes;
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable each : open) {
            each.close();
        }
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a mute made on one server is in force on the other, for the moderator and for everybody")
    void followsTheModerator() {
        AlertMutes smp = server("smp");
        AlertMutes arena = server("arena");

        smp.mute(mod, CheckType.GRIM_COMBAT);
        smp.muteForEveryone(CheckType.GRIM_PACKETS);
        assertThat(smp.flush()).isTrue();
        hub.drain();

        assertThat(arena.isMuted(mod, CheckType.GRIM_COMBAT)).isTrue();
        assertThat(arena.isMuted(other, CheckType.GRIM_COMBAT)).isFalse();
        assertThat(arena.isMuted(other, CheckType.GRIM_PACKETS)).isTrue();
    }

    @Test
    @DisplayName("two servers muting different things at once keep both, and an unmute travels too")
    void bothKeepTheirs() {
        AlertMutes smp = server("smp");
        AlertMutes arena = server("arena");

        smp.mute(mod, CheckType.GRIM_COMBAT);
        arena.mute(other, CheckType.GRIM_WORLD);
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();
        hub.drain();

        assertThat(smp.isMuted(other, CheckType.GRIM_WORLD)).isTrue();
        assertThat(arena.isMuted(mod, CheckType.GRIM_COMBAT)).isTrue();

        arena.unmute(mod, CheckType.GRIM_COMBAT);
        arena.flush();
        hub.drain();
        assertThat(smp.isMuted(mod, CheckType.GRIM_COMBAT)).isFalse();
        assertThat(smp.isMuted(other, CheckType.GRIM_WORLD)).isTrue();
    }

    @Test
    @DisplayName("a mute not yet written survives another server's news")
    void unwrittenSurvives() {
        AlertMutes smp = server("smp");
        AlertMutes arena = server("arena");
        smp.mute(mod, CheckType.GRIM_COMBAT);

        arena.muteForEveryone(CheckType.GRIM_PACKETS);
        arena.flush();
        hub.drain();

        assertThat(smp.isMuted(mod, CheckType.GRIM_COMBAT)).isTrue();
        assertThat(smp.isMuted(other, CheckType.GRIM_PACKETS)).isTrue();
        smp.flush();
        hub.drain();
        assertThat(arena.isMuted(mod, CheckType.GRIM_COMBAT)).isTrue();
    }
}
