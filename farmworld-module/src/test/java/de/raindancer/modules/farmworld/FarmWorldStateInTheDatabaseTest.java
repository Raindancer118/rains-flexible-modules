package de.raindancer.modules.farmworld;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.farmworld.model.WorldSet;
import de.raindancer.modules.farmworld.store.FarmWorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
@DisplayName("farm world state on the database: the schedule, the one-time migration, no marker file")
class FarmWorldStateInTheDatabaseTest {

    @TempDir
    Path plugins;

    private Database documentsDatabase;
    private Database own;
    private Database legacy;
    private Documents documents;
    private Path definitions;

    @BeforeEach
    void open() {
        documentsDatabase = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(documentsDatabase, null, plugins, "smp", System::currentTimeMillis);
        own = TestDatabases.open(plugins.resolve("farmworld.db"), FarmWorldState.SCHEMA);
        legacy = TestDatabases.open(plugins.resolve("core.db"), FarmWorldState.SCHEMA);
        definitions = plugins.resolve("RainsFarmWorlds").resolve("farmworlds.yml");
    }

    @AfterEach
    void close() {
        own.close();
        legacy.close();
        documentsDatabase.close();
    }

    private YamlStore store() {
        return new YamlStore(definitions, documents, Documents.Scope.SERVER);
    }

    private YamlStore notes() {
        return new YamlStore(definitions.resolveSibling(FarmWorldState.stateNameOf(definitions)), documents,
                Documents.Scope.SERVER);
    }

    private FarmWorldState state() {
        return new FarmWorldState(store(), notes(), own);
    }

    private void seedLegacy(String name, Long madeAt, Long triedAt) {
        legacy.write(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO farm_world (name, made_at, tried_at) VALUES (?, ?, ?)")) {
                statement.setString(1, name);
                if (madeAt == null) {
                    statement.setNull(2, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(2, madeAt);
                }
                if (triedAt == null) {
                    statement.setNull(3, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(3, triedAt);
                }
                statement.execute();
            }
        });
    }

    @Test
    @DisplayName("definitions are a document and the times are rows; both survive a restart")
    void roundTrip() {
        FarmWorldState first = state();
        first.define(WorldSet.of("farmworld"));
        first.recordRegenerated("farmworld", Instant.ofEpochMilli(7000L));
        first.flush();

        FarmWorldState second = state();
        second.load();

        assertThat(second.byName("farmworld")).isPresent();
        assertThat(second.lastRegenerated("farmworld")).contains(Instant.ofEpochMilli(7000L));
        assertThat(definitions).doesNotExist();
    }

    @Test
    @DisplayName("the old schedule is copied once, nulls stay nulls, and what the module recorded since is never overwritten")
    void migrates() {
        seedLegacy("farmworld", 1000L, 2000L);
        seedLegacy("fresh", null, null);
        FarmWorldState state = state();
        state.define(WorldSet.of("farmworld"));
        state.define(WorldSet.of("fresh"));
        state.flush();

        state.migrateFrom(legacy);
        state.load();
        assertThat(state.lastRegenerated("farmworld")).contains(Instant.ofEpochMilli(1000L));
        assertThat(state.lastRegenerated("fresh")).isEmpty();

        state.recordRegenerated("farmworld", Instant.ofEpochMilli(5000L));
        state.flush();
        seedLegacy("latecomer", 9000L, 9000L);
        state().migrateFrom(legacy);
        FarmWorldState again = state();
        again.load();
        assertThat(again.lastRegenerated("farmworld")).contains(Instant.ofEpochMilli(5000L));
    }

    @Test
    @DisplayName("that it is done is a key in a state document, not a file beside it, and a later boot does not read the old database")
    void markerIsAKey() {
        seedLegacy("farmworld", 1000L, 2000L);
        FarmWorldState state = state();
        state.define(WorldSet.of("farmworld"));
        state.flush();
        state.migrateFrom(legacy);

        assertThat(notes().read().getBoolean("migrated-from-core")).isTrue();
        assertThat(definitions.resolveSibling("farmworlds.yml.migrated-from-core")).doesNotExist();

        seedLegacy("latecomer", 9000L, 9000L);
        FarmWorldState later = state();
        later.define(WorldSet.of("latecomer"));
        later.flush();
        later.migrateFrom(legacy);
        later.load();
        assertThat(later.lastRegenerated("latecomer")).isEmpty();
    }

    @Test
    @DisplayName("a marker file left by 1.x counts as done, becomes the key, and is renamed")
    void oldMarkerIsHonoured() throws Exception {
        Files.createDirectories(definitions.getParent());
        Path oldMarker = definitions.resolveSibling("farmworlds.yml.migrated-from-core");
        Files.createFile(oldMarker);
        seedLegacy("farmworld", 1000L, 2000L);
        FarmWorldState state = state();
        state.define(WorldSet.of("farmworld"));
        state.flush();

        state.migrateFrom(legacy);
        state.load();

        assertThat(state.lastRegenerated("farmworld")).isEmpty();
        assertThat(notes().read().getBoolean("migrated-from-core")).isTrue();
        assertThat(oldMarker).doesNotExist();
        assertThat(definitions.resolveSibling("farmworlds.yml.migrated-from-core.imported-2.0.0")).exists();
    }
}
