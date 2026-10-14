package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Dialect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Optional;

/**
 * A database that lets another server's work run in the middle of one of its writes: just before the first
 * statement containing a given text, so a write has read what it read and not yet changed anything. Only
 * where writers do not lock each other out at the start (PostgreSQL).
 */
public final class Interleaved implements Database {

    private final Database inner;
    private volatile String trigger;
    private volatile Runnable action;

    public Interleaved(Database inner) {
        this.inner = inner;
    }

    /** Runs {@code other} once, before the next statement that contains {@code sql}. */
    public void before(String sql, Runnable other) {
        action = other;
        trigger = sql;
    }

    @Override
    public boolean write(Work work) {
        return inner.write(connection -> work.run(watched(connection)));
    }

    private Connection watched(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String waiting = trigger;
                    if (waiting != null && method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.contains(waiting)) {
                        Runnable run = action;
                        trigger = null;
                        action = null;
                        run.run();
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    @Override
    public <T> Optional<T> read(Query<T> query) {
        return inner.read(query);
    }

    @Override
    public int version() {
        return inner.version();
    }

    @Override
    public boolean isUsable() {
        return inner.isUsable();
    }

    @Override
    public Path file() {
        return inner.file();
    }

    @Override
    public String describe() {
        return inner.describe();
    }

    @Override
    public Dialect dialect() {
        return inner.dialect();
    }

    @Override
    public int workOnServerThread() {
        return inner.workOnServerThread();
    }

    @Override
    public void close() {
        inner.close();
    }
}
