package de.raindancer.modules.jobs.store;

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
    private final List<Database> databases = new ArrayList<>();

    TwoServers(Path folder) {
        this.file = folder.resolve("jobs.db");
    }

    Database database() {
        Database database = TestDatabases.open(file, JobsDatabase.SCHEMA);
        databases.add(database);
        return database;
    }

    GoalBook goals(String server) {
        GoalBook book = new GoalBook(database());
        book.load();
        book.connect(hub.bus(server));
        return book;
    }

    QuestBook quests(String server) {
        QuestBook book = new QuestBook(database());
        book.load();
        book.connect(hub.bus(server));
        return book;
    }

    OrderBook orders(String server) {
        OrderBook book = new OrderBook(database());
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
