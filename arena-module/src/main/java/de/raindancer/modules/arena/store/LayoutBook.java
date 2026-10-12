package de.raindancer.modules.arena.store;

import de.raindancer.modules.arena.model.Layout;

import java.util.function.UnaryOperator;

/** The layout in memory, read from disk once, written back with every change. */
public final class LayoutBook {

    private final LayoutStore store;
    private volatile Layout current;

    public LayoutBook(LayoutStore store) {
        this.store = store;
        this.current = store.load();
    }

    public Layout current() {
        return current;
    }

    /** @return whether it reached disk; the change holds in memory either way, so the arena keeps working */
    public synchronized boolean update(UnaryOperator<Layout> change) {
        current = change.apply(current);
        return store.save(current);
    }

    public synchronized void reload() {
        current = store.load();
    }
}
