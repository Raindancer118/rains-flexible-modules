package de.raindancer.modules.manhunt.stats;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The last hunts this server played, each with its whole timeline — {@code hunts.yml}. Numbered for
 * good: a number is never reused, even when the oldest record has been let go, so "hunt #12" in
 * somebody's chat stays the hunt it was.
 */
public final class HistoryStore {

    private final YamlStore store;
    private final List<HuntRecord> hunts = new ArrayList<>();
    private int nextNumber = 1;

    public HistoryStore(Path file) {
        this.store = new YamlStore(file);
        load();
    }

    public synchronized int nextNumber() {
        return nextNumber;
    }

    /** Newest first. */
    public synchronized List<HuntRecord> all() {
        return List.copyOf(hunts);
    }

    public synchronized Optional<HuntRecord> find(int number) {
        return hunts.stream().filter(hunt -> hunt.number() == number).findFirst();
    }

    public synchronized Optional<HuntRecord> latest() {
        return hunts.isEmpty() ? Optional.empty() : Optional.of(hunts.getFirst());
    }

    /** Adds a finished hunt and lets the oldest go past {@code keep}; saved at once. */
    public synchronized void add(HuntRecord record, int keep) {
        nextNumber = Math.max(nextNumber, record.number() + 1);
        hunts.removeIf(hunt -> hunt.number() == record.number());
        hunts.addFirst(record);
        hunts.sort(Comparator.comparingInt(HuntRecord::number).reversed());
        while (hunts.size() > Math.max(0, keep)) {
            hunts.removeLast();
        }
        List<HuntRecord> snapshot = List.copyOf(hunts);
        int next = nextNumber;
        store.write(yaml -> write(yaml, snapshot, next));
    }

    private void load() {
        if (!store.exists()) {
            return;
        }
        YamlConfiguration yaml = store.read();
        nextNumber = Math.max(1, yaml.getInt("next-number", 1));
        ConfigurationSection section = yaml.getConfigurationSection("hunts");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection hunt = section.getConfigurationSection(key);
            if (hunt != null) {
                read(hunt).ifPresent(hunts::add);
            }
        }
        hunts.sort(Comparator.comparingInt(HuntRecord::number).reversed());
    }

    private static Optional<HuntRecord> read(ConfigurationSection hunt) {
        try {
            List<PlayerResult> players = new ArrayList<>();
            ConfigurationSection ps = hunt.getConfigurationSection("players");
            if (ps != null) {
                for (String key : ps.getKeys(false)) {
                    ConfigurationSection p = ps.getConfigurationSection(key);
                    players.add(new PlayerResult(UUID.fromString(key), p.getString("name"), p.getBoolean("runner"),
                            p.getBoolean("won"), p.getBoolean("caught"), p.getInt("catches"), p.getInt("deaths"),
                            p.getLong("survived-millis"), p.getDouble("distance"), p.getInt("portals")));
                }
            }
            List<TimelineEvent> events = new ArrayList<>();
            ConfigurationSection es = hunt.getConfigurationSection("events");
            if (es != null) {
                for (String key : es.getKeys(false)) {
                    ConfigurationSection e = es.getConfigurationSection(key);
                    events.add(new TimelineEvent(e.getLong("at"),
                            TimelineEvent.Kind.valueOf(e.getString("kind")), id(e.getString("who")),
                            e.getString("who-name"), id(e.getString("other")), e.getString("other-name"),
                            e.getString("detail")));
                }
            }
            return Optional.of(new HuntRecord(hunt.getInt("number"), hunt.getLong("started-at"),
                    hunt.getLong("duration-millis"), hunt.getString("reason"),
                    HuntRecord.Winner.valueOf(hunt.getString("winner", "NOBODY")), players, events));
        } catch (RuntimeException unreadable) {
            return Optional.empty();   // a hand-edited entry costs that one hunt, never the file
        }
    }

    private static UUID id(String raw) {
        return raw == null ? null : UUID.fromString(raw);
    }

    private static void write(YamlConfiguration yaml, List<HuntRecord> hunts, int nextNumber) {
        yaml.set("next-number", nextNumber);
        for (HuntRecord hunt : hunts) {
            String at = "hunts." + hunt.number() + ".";
            yaml.set(at + "number", hunt.number());
            yaml.set(at + "started-at", hunt.startedAt());
            yaml.set(at + "duration-millis", hunt.durationMillis());
            yaml.set(at + "reason", hunt.reason());
            yaml.set(at + "winner", hunt.winner().name());
            for (PlayerResult p : hunt.players()) {
                String pa = at + "players." + p.id() + ".";
                yaml.set(pa + "name", p.name());
                yaml.set(pa + "runner", p.runner());
                yaml.set(pa + "won", p.won());
                yaml.set(pa + "caught", p.caught());
                yaml.set(pa + "catches", p.catches());
                yaml.set(pa + "deaths", p.deaths());
                yaml.set(pa + "survived-millis", p.survivedMillis());
                yaml.set(pa + "distance", p.distance());
                yaml.set(pa + "portals", p.portals());
            }
            int index = 0;
            for (TimelineEvent e : hunt.events()) {
                String ea = at + "events." + index++ + ".";
                yaml.set(ea + "at", e.atMillis());
                yaml.set(ea + "kind", e.kind().name());
                yaml.set(ea + "who", e.who() == null ? null : e.who().toString());
                yaml.set(ea + "who-name", e.whoName());
                yaml.set(ea + "other", e.other() == null ? null : e.other().toString());
                yaml.set(ea + "other-name", e.otherName());
                yaml.set(ea + "detail", e.detail());
            }
        }
    }
}
