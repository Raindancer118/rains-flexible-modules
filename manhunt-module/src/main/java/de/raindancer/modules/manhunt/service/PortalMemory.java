package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.service.TrackerCompass.Point;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each Runner was last seen leaving each world — the door a Hunter still in the Overworld is
 * pointed at once the Runner is in the Nether.
 *
 * <h2>Why per world rather than "the last portal"</h2>
 * A Runner who goes Overworld → Nether → back → Nether again has left the Overworld twice, possibly by
 * two different portals, and a Hunter standing in the Overworld wants the second one. A Runner who has
 * gone Overworld → Nether → End has left two different worlds, and which crossing is useful depends
 * entirely on which world the Hunter asking is standing in. One entry per (Runner, world) answers both
 * without keeping a history nobody reads: the newest crossing in a world replaces the older one, since
 * an older door is exactly the door the Runner is no longer behind.
 *
 * <h2>UUIDs, never {@code Player}</h2>
 * A live {@code Player} held in a long-lived map pins their inventory and their chunks in the heap for
 * as long as the map does — see the module's own note on the same rule in {@code ManhuntTeams}. This
 * class never sees a Bukkit type at all: {@code RunnerPortalListener} converts a real
 * {@code Location} into a {@link Point} at the door, which is also what makes the whole thing testable
 * without a server.
 *
 * <h2>Thread safety</h2>
 * Written from a Bukkit event and read from the compass' own timer, which on Folia are not the same
 * thread — hence {@link ConcurrentHashMap} rather than the plain map its size would otherwise call
 * for. Reads are of immutable {@link Point} records, so a reader either sees the crossing before an
 * update or the one after it, never a half-written position.
 */
public final class PortalMemory {

    private final Map<UUID, Map<String, Point>> crossings = new ConcurrentHashMap<>();

    /** Records that {@code runner} left {@code where}'s world at that spot. Nulls are ignored. */
    public void remember(UUID runner, Point where) {
        if (runner == null || where == null || where.worldName() == null) {
            return;
        }
        crossings.computeIfAbsent(runner, id -> new ConcurrentHashMap<>())
                .put(where.worldName(), where);
    }

    /** Where {@code runner} was last seen leaving {@code worldName}, if they ever were. */
    public Optional<Point> lastCrossingIn(UUID runner, String worldName) {
        if (runner == null || worldName == null) {
            return Optional.empty();
        }
        Map<String, Point> theirs = crossings.get(runner);
        return theirs == null ? Optional.empty() : Optional.ofNullable(theirs.get(worldName));
    }

    /** Forgets everything about {@code runner} — they left the hunt, so their doors are nobody's. */
    public void forget(UUID runner) {
        crossings.remove(runner);
    }

    /** Forgets every crossing, so the next hunt starts blind. Called when a run starts and ends. */
    public void clear() {
        crossings.clear();
    }
}
