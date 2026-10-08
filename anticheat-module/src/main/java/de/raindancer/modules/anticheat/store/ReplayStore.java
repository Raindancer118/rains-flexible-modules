package de.raindancer.modules.anticheat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.anticheat.model.ReplayFrame;
import de.raindancer.modules.anticheat.model.Replays;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Frozen replays, kept in {@code replays.yml} so a restart does not cost the staff their evidence. */
public final class ReplayStore {

    private final YamlStore store;
    private final Replays replays;
    private final AtomicBoolean dirty = new AtomicBoolean();

    public ReplayStore(Path dataFolder, Replays replays) {
        this.store = new YamlStore(dataFolder.resolve("replays.yml"));
        this.replays = replays;
    }

    public Replays replays() {
        return replays;
    }

    public void add(UUID who, Replays.Replay replay) {
        replays.add(who, replay);
        dirty.set(true);
    }

    public void load() {
        ConfigurationSection players = store.read().getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            try {
                UUID who = UUID.fromString(id);
                List<Replays.Replay> saved = new ArrayList<>();
                for (Map<?, ?> row : players.getMapList(id)) {
                    List<ReplayFrame> frames = new ArrayList<>();
                    Object raw = row.get("frames");
                    if (raw instanceof List<?> lines) {
                        for (Object line : lines) {
                            ReplayFrame.decode(String.valueOf(line)).ifPresent(frames::add);
                        }
                    }
                    saved.add(new Replays.Replay(((Number) row.get("at")).longValue(), String.valueOf(row.get("check")),
                            String.valueOf(row.get("detail")), List.copyOf(frames)));
                }
                replays.restore(who, saved);
            } catch (RuntimeException malformed) {
                // One player's broken replays cost that player's replays only.
            }
        }
    }

    public boolean flush() {
        if (!dirty.getAndSet(false)) {
            return true;
        }
        boolean written = store.write(yaml -> {
            for (UUID who : replays.everybody()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Replays.Replay replay : replays.of(who)) {
                    List<String> frames = replay.frames().stream().map(ReplayFrame::encode).toList();
                    rows.add(Map.of("at", replay.atMillis(), "check", replay.check(), "detail", replay.detail(), "frames", frames));
                }
                yaml.set("players." + who, rows);
            }
        });
        if (!written) {
            dirty.set(true);
        }
        return written;
    }
}
