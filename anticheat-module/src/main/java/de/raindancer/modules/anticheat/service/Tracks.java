package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** One {@link PlayerTrack} per online player. */
public final class Tracks implements IAntiCheatService {

    private final Map<UUID, PlayerTrack> tracks = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Tracks(LongSupplier clock) {
        this.clock = clock;
    }

    public PlayerTrack of(Player player) {
        return tracks.computeIfAbsent(player.getUniqueId(), id -> new PlayerTrack(id, player.getName(), clock));
    }

    public Optional<PlayerTrack> find(UUID id) {
        return Optional.ofNullable(tracks.get(id));
    }

    public Collection<PlayerTrack> all() {
        return List.copyOf(tracks.values());
    }

    public void forget(UUID id) {
        tracks.remove(id);
    }

    public int size() {
        return tracks.size();
    }

    /** Floodgate gives Bedrock players an id whose upper half is zero. */
    public static boolean isBedrock(UUID id) {
        return id != null && id.getMostSignificantBits() == 0;
    }

    @Override
    public void settings(AntiCheatSettings settings) {
        // Holds players, not numbers: nothing here reads the file.
    }

    @Override
    public String describe() {
        return "what the checks remember about each online player";
    }
}
