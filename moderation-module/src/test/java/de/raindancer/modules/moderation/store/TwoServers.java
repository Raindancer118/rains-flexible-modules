package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Servers of one network side by side: their own Documents over one database, one bus between them. */
final class TwoServers implements AutoCloseable {

    final LocalBus.Hub hub = new LocalBus.Hub();
    final Path plugins;
    private final List<Database> databases = new ArrayList<>();

    TwoServers(Path plugins) {
        this.plugins = plugins;
    }

    Documents server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        return new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
    }

    /** A server whose database cannot be used: what a store does when it cannot read. */
    Documents brokenServer(String id) throws java.io.IOException {
        Database database = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        databases.add(database);
        return new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
    }

    /** This module's network document {@code name} as {@code server} sees it. */
    YamlStore network(Documents server, String name) {
        return new YamlStore(plugins.resolve("RainsModeration").resolve(name), server, Documents.Scope.NETWORK);
    }

    /** Until every server has heard everything published so far. */
    void settle() {
        hub.drain();
    }

    @Override
    public void close() {
        databases.forEach(Database::close);
    }
}
