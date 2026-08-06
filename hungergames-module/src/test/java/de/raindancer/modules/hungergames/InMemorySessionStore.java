package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.modules.hungergames.store.SessionStore;

import java.util.Optional;

/** An in-memory {@link SessionStore} for tests; counts how many times it was saved to. */
final class InMemorySessionStore implements SessionStore {

    private SessionSnapshot snapshot;
    int saveCount;

    @Override
    public void save(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
        saveCount++;
    }

    @Override
    public Optional<SessionSnapshot> load() {
        return Optional.ofNullable(snapshot);
    }

    @Override
    public void clear() {
        snapshot = null;
    }
}
