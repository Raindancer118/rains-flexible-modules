package de.raindancer.modules.hungergames.model;

/**
 * Where the game logic gets the current time from.
 *
 * <h2>Why this exists instead of calling {@code System.currentTimeMillis()} directly</h2>
 * Two reasons, and both are about not being able to control the wall clock. A unit test that wants to
 * assert "running since 1_000_000ms" cannot wait for the system clock to say that, so every clock read in
 * {@code store.GameSession} goes through this port and a test hands in a fixed one. And {@code /speedup}
 * — a gamemaster fast-forwarding a slow lobby — runs the round on a virtual clock that advances faster
 * than real seconds; nothing in the game logic needs to know which kind of clock it is holding, because
 * both look the same through this interface.
 */
@FunctionalInterface
public interface GameClock {

    /** The current instant, epoch milliseconds. */
    long nowMillis();

    /** The real system clock, for production use. */
    static GameClock system() {
        return System::currentTimeMillis;
    }
}
