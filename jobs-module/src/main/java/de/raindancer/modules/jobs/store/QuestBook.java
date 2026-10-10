package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everybody's quests for the day — quest-progress.yml, held in memory. Progress is written with the next
 * flush; a quest being paid is written at once, so a restart never pays it twice. An unreadable file is never
 * written over.
 */
public final class QuestBook {

    /** Days kept for a player who has not been seen; owed quests are kept whatever their age. */
    private static final int KEPT_DAYS = 7;

    private final YamlStore store;
    private final Map<UUID, QuestDay> days = new LinkedHashMap<>();
    private volatile boolean readable = true;
    private boolean dirty;

    public QuestBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        days.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection each = players.getConfigurationSection(id);
            if (each == null) {
                continue;
            }
            try {
                List<Quest> quests = new ArrayList<>();
                for (Map<?, ?> written : each.getMapList("quests")) {
                    quests.add(new Quest(String.valueOf(written.get("template")), number(written.get("amount")),
                            Money.of(number(written.get("pay"))), number(written.get("progress")),
                            Quest.State.valueOf(String.valueOf(written.containsKey("state") ? written.get("state") : "OPEN"))));
                }
                days.put(UUID.fromString(id), new QuestDay(each.getString("day", ""), each.getInt("tier"), quests,
                        each.getStringList("before"), each.getInt("rerolls")));
            } catch (IllegalArgumentException unreadable) {
                // One player's day that cannot be read is left out; they are given a new one.
            }
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? (int) Math.min(Integer.MAX_VALUE, number.longValue()) : 0;
    }

    public boolean readable() {
        return readable;
    }

    public synchronized Optional<QuestDay> of(UUID player) {
        return Optional.ofNullable(days.get(player));
    }

    /** Everybody with a quest whose payment was refused. */
    public synchronized Map<UUID, QuestDay> owing() {
        Map<UUID, QuestDay> owing = new LinkedHashMap<>();
        days.forEach((player, day) -> {
            if (day.quests().stream().anyMatch(quest -> quest.state() == Quest.State.OWED)) {
                owing.put(player, day);
            }
        });
        return owing;
    }

    /** Puts a player's day, written with the next flush. */
    public synchronized void put(UUID player, QuestDay day) {
        days.put(player, day);
        dirty = true;
    }

    /** Puts a player's day and writes it now. @return false, and nothing changed, when it could not be written */
    public synchronized boolean putNow(UUID player, QuestDay day) {
        if (!readable) {
            return false;
        }
        QuestDay before = days.put(player, day);
        if (save()) {
            return true;
        }
        if (before == null) {
            days.remove(player);
        } else {
            days.put(player, before);
        }
        return false;
    }

    public synchronized boolean clear(UUID player) {
        QuestDay before = days.remove(player);
        if (before == null || save()) {
            return true;
        }
        days.put(player, before);
        return false;
    }

    /** Writes what changed since the last write, dropping days long gone. */
    public synchronized void flush(LocalDate today) {
        String oldest = today.minusDays(KEPT_DAYS).toString();
        boolean pruned = days.entrySet().removeIf(entry -> entry.getValue().day().compareTo(oldest) < 0
                && entry.getValue().quests().stream().noneMatch(quest -> quest.state() == Quest.State.OWED));
        if ((dirty || pruned) && readable) {
            save();
        }
    }

    private boolean save() {
        Map<UUID, QuestDay> copy = Map.copyOf(days);
        boolean written = store.write(yaml -> copy.forEach((player, day) -> {
            String path = "players." + player;
            yaml.set(path + ".day", day.day());
            yaml.set(path + ".tier", day.tier());
            yaml.set(path + ".before", day.before());
            yaml.set(path + ".rerolls", day.rerolls());
            List<Map<String, Object>> quests = new ArrayList<>();
            for (Quest quest : day.quests()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("template", quest.template());
                line.put("amount", quest.amount());
                line.put("pay", quest.pay().minor());
                line.put("progress", quest.progress());
                line.put("state", quest.state().name());
                quests.add(line);
            }
            yaml.set(path + ".quests", quests);
        }));
        if (written) {
            dirty = false;
        }
        return written;
    }
}
