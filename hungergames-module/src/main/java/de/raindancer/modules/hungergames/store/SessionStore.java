package de.raindancer.modules.hungergames.store;

import de.raindancer.modules.hungergames.model.SessionSnapshot;

import java.util.Optional;

/**
 * The port over which a round's state is persisted.
 *
 * <p>A YAML file under {@code RainsCore}'s {@code data.store.YamlStore} is the production implementation;
 * a server restart mid-round reconstructs the session from whatever this last saved. Tests use an
 * in-memory implementation and get the same restart-safety guarantee without touching a disk.
 */
public interface SessionStore {

    /** Persists the complete session state. Called after every mutation — see {@code store.GameSession}. */
    void save(SessionSnapshot snapshot);

    /** The last saved state, if there is one. */
    Optional<SessionSnapshot> load();

    /** Removes the saved state (after a reset, or once a round has ended and been consumed). */
    void clear();
}
