package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Servers of one network side by side: each its own connection to the one database, one bus between them. */
final class TwoServers implements AutoCloseable {

    final LocalBus.Hub hub = new LocalBus.Hub();
    final Path file;
    final List<String> problems = new ArrayList<>();
    private final List<Database> databases = new ArrayList<>();

    TwoServers(Path folder) {
        this.file = folder.resolve("guilds.db");
    }

    private Database common;

    /**
     * A server's connection. On PostgreSQL each server has its own, as on a network. A SQLite file has one writer
     * connection (a second one fails a transaction that reads before it writes, instead of waiting), so there the
     * servers share one, as stores of one server do.
     */
    Database database() {
        if (TestDatabases.dialect() == de.raindancer.core.data.sql.Dialect.SQLITE) {
            if (common == null) {
                common = separate();
            }
            return common;
        }
        return separate();
    }

    /** A connection of its own, that a test may close to make that server's database go away. */
    Database separate() {
        Database database = TestDatabases.open(file, GuildsDatabase.SCHEMA);
        databases.add(database);
        return database;
    }

    GuildBook book(String server) {
        GuildBook book = new GuildBook(database(), problems::add);
        book.load();
        book.connect(hub.bus(server));
        return book;
    }

    /** A book on a connection that is not connected to the bus, as a restarted server's would be. */
    GuildBook restarted() {
        GuildBook book = new GuildBook(database(), problems::add);
        book.load();
        return book;
    }

    ProgressBook progress(String server) {
        ProgressBook book = new ProgressBook(database());
        book.load();
        book.connect(hub.bus(server));
        return book;
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
