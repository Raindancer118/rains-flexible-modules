package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Servers of one network in a test: each its own Documents over one database, hearing each other over one hub. */
final class Servers implements AutoCloseable {

    private final Path plugins;
    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    Servers(Path plugins) {
        this.plugins = plugins;
    }

    Documents server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        return new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
    }

    YamlStore network(Documents on, String name) {
        return new YamlStore(file(name), on, Documents.Scope.NETWORK);
    }

    Path file(String name) {
        return plugins.resolve("RainsEssentials").resolve(name);
    }

    void heard() {
        hub.drain();
    }

    @Override
    public void close() {
        databases.forEach(Database::close);
    }
}
