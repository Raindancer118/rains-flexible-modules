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

    public void clear(UUID who) {
        replays.clear(who);
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
                            String.valueOf(row.get("detail")), List.copyOf(frames), loadout(row.get("loadout"))));
                }
                replays.restore(who, saved);
            } catch (RuntimeException malformed) {
                // One player's broken replays cost that player's replays only.
            }
        }
    }

    /** Empty slots as "" — a YAML list keeps its length that way. */
    private static Map<String, Object> written(de.raindancer.modules.anticheat.model.ReplayLoadout loadout) {
        java.util.function.Function<String, String> slot = item -> item == null ? "" : item;
        return Map.of("armour", loadout.armour().stream().map(slot).toList(),
                "off-hand", slot.apply(loadout.offHand()),
                "hotbar", loadout.hotbar().stream().map(slot).toList());
    }

    private static de.raindancer.modules.anticheat.model.ReplayLoadout loadout(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.function.Function<Object, List<String>> slots = list -> list instanceof List<?> items
                ? items.stream().map(item -> item == null || String.valueOf(item).isEmpty() ? null : String.valueOf(item)).toList()
                : List.of();
        Object offHand = map.get("off-hand");
        return new de.raindancer.modules.anticheat.model.ReplayLoadout(slots.apply(map.get("armour")),
                offHand == null || String.valueOf(offHand).isEmpty() ? null : String.valueOf(offHand), slots.apply(map.get("hotbar")));
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
                    Map<String, Object> row = new java.util.LinkedHashMap<>(Map.of("at", replay.atMillis(), "check", replay.check(),
                            "detail", replay.detail(), "frames", frames));
                    if (replay.loadout() != null) {
                        row.put("loadout", written(replay.loadout()));
                    }
                    rows.add(row);
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
