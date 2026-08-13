package de.raindancer.modules.mannequin.store;

import de.raindancer.modules.mannequin.model.Leaderboard;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.TrainingSession;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every mannequin, while the server is up: its stored data, the live entity currently representing
 * it (if any world has it loaded right now), and its running combat tally.
 *
 * <h2>Thread safety</h2>
 * Every method is {@code synchronized}. A hit is recorded from the combat listener while a screen
 * is reading the same mannequin's stats, and a world unloading removes the live-entity mapping
 * while another region's timer is iterating every tracked mannequin for the shield check.
 */
public final class MannequinRegistry {

    private final Map<String, Mannequin> byId = new LinkedHashMap<>();
    private final Map<String, UUID> liveEntities = new LinkedHashMap<>();
    private final Map<String, TrainingSession> sessions = new LinkedHashMap<>();
    private final Map<String, Leaderboard> leaderboards = new LinkedHashMap<>();
    private long highest;

    // ---------------------------------------------------------------------------- the data

    public synchronized void put(Mannequin mannequin) {
        if (mannequin == null) {
            return;
        }
        byId.put(mannequin.id(), mannequin);
        rememberNumber(mannequin.id());
        sessions.putIfAbsent(mannequin.id(), TrainingSession.EMPTY);
    }

    /** @return whether there was one to take out */
    public synchronized boolean remove(String id) {
        if (id == null) {
            return false;
        }
        liveEntities.remove(id);
        sessions.remove(id);
        leaderboards.remove(id);
        return byId.remove(id) != null;
    }

    public synchronized Optional<Mannequin> get(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public synchronized List<Mannequin> all() {
        return List.copyOf(byId.values());
    }

    public synchronized List<Mannequin> ownedBy(UUID owner) {
        List<Mannequin> found = new ArrayList<>();
        for (Mannequin mannequin : byId.values()) {
            if (mannequin.owner().equals(owner)) {
                found.add(mannequin);
            }
        }
        return found;
    }

    public synchronized List<Mannequin> inWorld(String world) {
        List<Mannequin> found = new ArrayList<>();
        for (Mannequin mannequin : byId.values()) {
            if (mannequin.world().equalsIgnoreCase(world)) {
                found.add(mannequin);
            }
        }
        return found;
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized String nextId() {
        return "MQ" + (++highest);
    }

    private void rememberNumber(String id) {
        if (!id.startsWith("MQ")) {
            return;
        }
        try {
            highest = Math.max(highest, Long.parseLong(id.substring(2)));
        } catch (NumberFormatException notOneOfOurs) {
            // Left alone, same as RtpLocationRegistry.
        }
    }

    // ---------------------------------------------------------------------------- the live entity

    /** Which real entity is currently representing this mannequin, if its world is loaded. */
    public synchronized Optional<UUID> liveEntity(String id) {
        return Optional.ofNullable(id == null ? null : liveEntities.get(id));
    }

    public synchronized void bindEntity(String id, UUID entityId) {
        if (id != null && entityId != null) {
            liveEntities.put(id, entityId);
        }
    }

    /** The world was unloaded, or the entity was otherwise removed. The stored record stays. */
    public synchronized void unbindEntity(String id) {
        if (id != null) {
            liveEntities.remove(id);
        }
    }

    /** The mannequin id that this live entity represents, if any. */
    public synchronized Optional<String> idFor(UUID entityId) {
        if (entityId == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, UUID> entry : liveEntities.entrySet()) {
            if (entry.getValue().equals(entityId)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------- the training session

    public synchronized TrainingSession sessionFor(String id) {
        return sessions.getOrDefault(id, TrainingSession.EMPTY);
    }

    public synchronized void updateSession(String id, TrainingSession session) {
        if (id != null && session != null) {
            sessions.put(id, session);
        }
    }

    public synchronized void resetSession(String id) {
        if (id != null) {
            sessions.put(id, TrainingSession.EMPTY);
            // The leaderboard is the same "current session" concept as the tally, told per player
            // and per weapon instead of as one running total — the one reset button clears both.
            leaderboards.remove(id);
        }
    }

    // ---------------------------------------------------------------------------- the leaderboard

    public synchronized Leaderboard leaderboardFor(String id) {
        return leaderboards.getOrDefault(id, Leaderboard.EMPTY);
    }

    public synchronized void recordLeaderboardHit(String id, UUID player, Material weapon,
                                                  ItemStack sample, double damage) {
        if (id == null || player == null || weapon == null) {
            return;
        }
        Leaderboard current = leaderboards.getOrDefault(id, Leaderboard.EMPTY);
        leaderboards.put(id, current.withHit(player, weapon, sample, damage));
    }
}
