package de.raindancer.modules.speedrun;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One past run, as the history keeps it: who raced, what category, how long, how it ended, and its
 * whole timeline — every split, death, pause and clock edit.
 *
 * <h2>Ranked, or only remembered</h2>
 * Every run is kept. Only a {@linkplain #ranked() ranked} one counts toward personal bests, records
 * and leaderboards: it reached the goal, it was played from its own start rather than picked up by a
 * resume, and nobody set its clock by hand. A resumed or edited run stays in the history, flagged,
 * so the summary still shows what happened — it just does not sit on a leaderboard beside runs
 * whose time nobody touched. {@code rank-edited-runs} lifts that for a server that wants it.
 *
 * @param labels what each split's milestone was called, so a summary can name a mode's own
 *               milestones after the mode is gone
 */
public record SpeedrunRunRecord(String id, SpeedrunCategory category, long startedAt, Duration time,
                                String outcome, boolean completed, long seed,
                                Map<UUID, String> participants, List<SpeedrunTimeline.Entry> timeline,
                                Map<String, String> labels) {

    public SpeedrunRunRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        time = time == null || time.isNegative() ? Duration.ZERO : time;
        outcome = outcome == null ? "" : outcome;
        participants = Map.copyOf(participants == null ? Map.of() : participants);
        timeline = List.copyOf(timeline == null ? List.of() : timeline);
        labels = Map.copyOf(labels == null ? Map.of() : labels);
    }

    public boolean resumed() {
        return has(SpeedrunTimeline.Kind.RESUMED);
    }

    public boolean clockEdited() {
        return has(SpeedrunTimeline.Kind.CLOCK_EDIT);
    }

    public int pauses() {
        return (int) timeline.stream().filter(entry -> entry.kind() == SpeedrunTimeline.Kind.PAUSE).count();
    }

    public int deaths() {
        return (int) timeline.stream().filter(entry -> entry.kind() == SpeedrunTimeline.Kind.DEATH).count();
    }

    /** Reached the goal, played from its own start, clock untouched. */
    public boolean ranked() {
        return ranked(false);
    }

    /** @param editedCount whether resumed and hand-edited runs are ranked too — {@code rank-edited-runs} */
    public boolean ranked(boolean editedCount) {
        return completed && (editedCount || (!resumed() && !clockEdited()));
    }

    public int playerCount() {
        return participants.size();
    }

    public boolean raced(UUID player) {
        return participants.containsKey(player);
    }

    public List<SpeedrunTimeline.Entry> splits() {
        return timeline.stream().filter(entry -> entry.kind() == SpeedrunTimeline.Kind.SPLIT).toList();
    }

    public Optional<Duration> splitAt(String milestoneId) {
        return splits().stream().filter(entry -> entry.detail().equals(milestoneId))
                .map(SpeedrunTimeline.Entry::at).findFirst();
    }

    /** What a milestone is called — its recorded label, the built-in one, or its id. */
    public String labelOf(String milestoneId) {
        String recorded = labels.get(milestoneId);
        if (recorded != null) {
            return recorded;
        }
        return SpeedrunMilestones.builtIn(milestoneId).map(SpeedrunMilestone::label).orElse(milestoneId);
    }

    public String nameOf(UUID player) {
        return player == null ? "" : participants.getOrDefault(player, player.toString().substring(0, 8));
    }

    private boolean has(SpeedrunTimeline.Kind kind) {
        return timeline.stream().anyMatch(entry -> entry.kind() == kind);
    }

    // ---------------------------------------------------------------------------- storage

    void writeTo(ConfigurationSection section) {
        section.set("category", category.key());
        section.set("started", startedAt);
        section.set("millis", time.toMillis());
        section.set("outcome", outcome);
        section.set("completed", completed);
        section.set("seed", seed);
        ConfigurationSection players = section.createSection("players");
        participants.forEach((uuid, name) -> players.set(uuid.toString(), name));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SpeedrunTimeline.Entry entry : timeline) {
            Map<String, Object> written = new LinkedHashMap<>();
            written.put("kind", entry.kind().name());
            written.put("at", entry.at().toMillis());
            written.put("who", entry.who() == null ? "" : entry.who().toString());
            written.put("detail", entry.detail());
            entries.add(written);
        }
        section.set("timeline", entries);
        ConfigurationSection named = section.createSection("labels");
        labels.forEach(named::set);
    }

    /** Empty for a section that is not a run — a hand edit gone wrong is skipped, not fatal. */
    static Optional<SpeedrunRunRecord> readFrom(String id, ConfigurationSection section) {
        if (section == null) {
            return Optional.empty();
        }
        Optional<SpeedrunCategory> category = SpeedrunCategory.fromKey(section.getString("category"));
        if (category.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, String> participants = new LinkedHashMap<>();
        ConfigurationSection players = section.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                uuid(key).ifPresent(uuid -> participants.put(uuid, players.getString(key, "")));
            }
        }
        List<SpeedrunTimeline.Entry> entries = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("timeline")) {
            try {
                SpeedrunTimeline.Kind kind = SpeedrunTimeline.Kind.valueOf(String.valueOf(raw.get("kind")));
                long at = raw.get("at") instanceof Number number ? number.longValue() : 0L;
                UUID who = uuid(String.valueOf(raw.get("who"))).orElse(null);
                Object detail = raw.get("detail");
                entries.add(new SpeedrunTimeline.Entry(kind, Duration.ofMillis(at), who,
                        detail == null ? "" : String.valueOf(detail)));
            } catch (IllegalArgumentException unknownKind) {
                // an entry kind from a newer version: skipped, the rest of the run still reads
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        ConfigurationSection named = section.getConfigurationSection("labels");
        if (named != null) {
            for (String key : named.getKeys(false)) {
                labels.put(key, named.getString(key, key));
            }
        }
        return Optional.of(new SpeedrunRunRecord(id, category.get(), section.getLong("started"),
                Duration.ofMillis(section.getLong("millis")), section.getString("outcome", ""),
                section.getBoolean("completed"), section.getLong("seed"), participants, entries, labels));
    }

    private static Optional<UUID> uuid(String text) {
        try {
            return text == null || text.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notOne) {
            return Optional.empty();
        }
    }
}
