package de.raindancer.modules.rtp.store;

import de.raindancer.modules.rtp.model.PreparedSpot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The prepared spots, while the server is up.
 *
 * <h2>Thread safety</h2>
 * Every method is {@code synchronized}. A spot arrives from the daily top-up or a {@code /rtp prepare},
 * both off the server thread, while a player's own request is reading the pool for somewhere to land —
 * so a caller iterating one while another adds to it is the ordinary case here, not an edge one.
 */
public final class RtpLocationRegistry {

    /** What a prepared spot's id starts with, so one is recognisable beside a report's {@code R}. */
    public static final String PREFIX = "L";

    private final Map<String, PreparedSpot> byId = new LinkedHashMap<>();
    private long highest;

    /** Puts one in, replacing any with the same id. */
    public synchronized void add(PreparedSpot spot) {
        if (spot == null) {
            return;
        }
        byId.put(spot.id(), spot);
        rememberNumber(spot.id());
    }

    /** @return whether there was one to take out */
    public synchronized boolean remove(String id) {
        return id != null && byId.remove(id) != null;
    }

    /** Marks a spot as sent to this player, if it is still here. */
    public synchronized void markUsed(String id, UUID player) {
        PreparedSpot spot = id == null ? null : byId.get(id);
        if (spot != null && player != null) {
            byId.put(id, spot.markUsedBy(player));
        }
    }

    /** Everything in a world this player has not already been sent to. */
    public synchronized List<PreparedSpot> availableFor(UUID player, String world) {
        if (player == null || world == null) {
            return List.of();
        }
        List<PreparedSpot> found = new ArrayList<>();
        for (PreparedSpot spot : byId.values()) {
            if (spot.world().equalsIgnoreCase(world) && !spot.usedBy(player)) {
                found.add(spot);
            }
        }
        return found;
    }

    public synchronized int size() {
        return byId.size();
    }

    /** Everything, for the auto-save — a snapshot, so a write in progress cannot see a half-built one. */
    public synchronized List<PreparedSpot> snapshot() {
        return List.copyOf(byId.values());
    }

    public synchronized void clear() {
        byId.clear();
        highest = 0;
    }

    /**
     * Reserves the next id. See {@code ReportRegistry#nextId} for why this moves the counter forward
     * rather than predicting it — the same race applies here: two searches finishing on two threads at
     * once must not both be told the same id.
     */
    public synchronized String nextId() {
        return PREFIX + (++highest);
    }

    /** Moves the high-water mark past this id, when it is one of ours — see {@code ReportRegistry}. */
    private void rememberNumber(String id) {
        if (!id.startsWith(PREFIX)) {
            return;
        }
        try {
            long number = Long.parseLong(id.substring(PREFIX.length()));
            highest = Math.max(highest, number);
        } catch (NumberFormatException notOneOfOurs) {
            // Left alone on purpose. See ReportRegistry.
        }
    }
}
