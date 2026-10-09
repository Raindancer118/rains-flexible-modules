package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The goals on the board, who has given what, and how big each kind has learned to be — goals.yml, held in
 * memory and written whole. A change that cannot be written is undone, and an unreadable file is never
 * written over: a hand-in has already taken the player's items, so it must not quietly vanish.
 */
public final class GoalBook {

    private final YamlStore store;
    private final Map<Long, Goal> active = new LinkedHashMap<>();
    private final Map<String, Integer> learned = new HashMap<>();
    private final Map<UUID, Money> owed = new LinkedHashMap<>();
    private long next = 1;
    private volatile boolean readable = true;
    private boolean dirty;

    public GoalBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        active.clear();
        learned.clear();
        owed.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        next = Math.max(1, yaml.getLong("next-number", 1));
        ConfigurationSection learnt = yaml.getConfigurationSection("learned");
        if (learnt != null) {
            learnt.getKeys(false).forEach(key -> learned.put(key, Math.max(1, learnt.getInt(key))));
        }
        ConfigurationSection debts = yaml.getConfigurationSection("owed");
        if (debts != null) {
            for (String id : debts.getKeys(false)) {
                try {
                    owed.put(UUID.fromString(id), Money.of(Math.max(0, debts.getLong(id))));
                } catch (IllegalArgumentException unreadable) {
                    // an unreadable line is left out; the others are still owed
                }
            }
        }
        ConfigurationSection goals = yaml.getConfigurationSection("goals");
        if (goals == null) {
            return;
        }
        for (String key : goals.getKeys(false)) {
            ConfigurationSection each = goals.getConfigurationSection(key);
            if (each == null) {
                continue;
            }
            try {
                Map<UUID, Integer> given = new LinkedHashMap<>();
                ConfigurationSection who = each.getConfigurationSection("given");
                if (who != null) {
                    for (String id : who.getKeys(false)) {
                        given.put(UUID.fromString(id), Math.max(0, who.getInt(id)));
                    }
                }
                Goal goal = new Goal(Long.parseLong(key), each.getString("template", ""), each.getString("title", ""),
                        each.getString("icon", "CHEST"), GoalKind.valueOf(each.getString("kind", "DELIVER")),
                        ItemSelection.parse(each.getStringList("items")), Math.max(1, each.getInt("amount", 1)),
                        each.getLong("started-at"), each.getLong("ends-at"), given);
                active.put(goal.number(), goal);
                next = Math.max(next, goal.number() + 1);
            } catch (IllegalArgumentException unreadable) {
                // One goal that cannot be read is left out; the rest of the board still works.
            }
        }
    }

    public boolean readable() {
        return readable;
    }

    public synchronized List<Goal> active() {
        return List.copyOf(active.values());
    }

    public synchronized Optional<Goal> goal(long number) {
        return Optional.ofNullable(active.get(number));
    }

    public synchronized long nextNumber() {
        return next;
    }

    public synchronized Optional<Integer> learned(String template) {
        return Optional.ofNullable(learned.get(template));
    }

    /** Puts a goal on the board. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean start(Goal goal) {
        if (!readable) {
            return false;
        }
        long before = next;
        active.put(goal.number(), goal);
        next = Math.max(next, goal.number() + 1);
        if (save()) {
            return true;
        }
        active.remove(goal.number());
        next = before;
        return false;
    }

    /**
     * Counts what a player gave, never past what the goal still needs.
     *
     * @return the goal as it is now, or empty when there is no such goal or it could not be saved
     */
    public synchronized Optional<Goal> add(long number, UUID player, int count) {
        Goal goal = active.get(number);
        if (goal == null || !readable) {
            return Optional.empty();
        }
        Goal changed = goal.with(player, count);
        active.put(number, changed);
        if (save()) {
            return Optional.of(changed);
        }
        active.put(number, goal);
        return Optional.empty();
    }

    /** The same, saved with the next flush rather than now — for catches, which come one at a time and often. */
    public synchronized Optional<Goal> addLater(long number, UUID player, int count) {
        Goal goal = active.get(number);
        if (goal == null || !readable) {
            return Optional.empty();
        }
        Goal changed = goal.with(player, count);
        active.put(number, changed);
        dirty = true;
        return Optional.of(changed);
    }

    /** Takes a goal off the board. @return it, or empty when there was none or it could not be saved */
    public synchronized Optional<Goal> end(long number) {
        Goal goal = active.get(number);
        if (goal == null || !readable) {
            return Optional.empty();
        }
        active.remove(number);
        if (save()) {
            return Optional.of(goal);
        }
        active.put(number, goal);
        return Optional.empty();
    }

    public synchronized boolean learn(String template, int amount) {
        if (!readable) {
            return false;
        }
        Integer before = learned.put(template, Math.max(1, amount));
        if (save()) {
            return true;
        }
        if (before == null) {
            learned.remove(template);
        } else {
            learned.put(template, before);
        }
        return false;
    }

    /** What the treasury refused to pay out, per player, still to be paid. */
    public synchronized Map<UUID, Money> owed() {
        return Map.copyOf(owed);
    }

    /** Adds to what a player is owed. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean owe(UUID player, Money amount) {
        if (!readable) {
            return false;
        }
        Money before = owed.get(player);
        owed.put(player, before == null ? amount : before.plus(amount));
        if (save()) {
            return true;
        }
        if (before == null) {
            owed.remove(player);
        } else {
            owed.put(player, before);
        }
        return false;
    }

    /** Strikes what a player was owed, once it is paid. */
    public synchronized boolean settle(UUID player) {
        Money before = owed.remove(player);
        if (before == null) {
            return true;
        }
        if (save()) {
            return true;
        }
        owed.put(player, before);
        return false;
    }

    /** Writes what was counted since the last write. */
    public synchronized void flush() {
        if (dirty && readable) {
            save();
        }
    }

    private boolean save() {
        Map<Long, Goal> goals = Map.copyOf(active);
        Map<String, Integer> sizes = Map.copyOf(learned);
        Map<UUID, Money> debts = Map.copyOf(owed);
        long number = next;
        boolean written = store.write(yaml -> {
            yaml.set("next-number", number);
            sizes.forEach((template, amount) -> yaml.set("learned." + template, amount));
            debts.forEach((player, money) -> yaml.set("owed." + player, money.minor()));
            goals.values().forEach(goal -> {
                String path = "goals." + goal.number();
                yaml.set(path + ".template", goal.template());
                yaml.set(path + ".title", goal.title());
                yaml.set(path + ".icon", goal.icon());
                yaml.set(path + ".kind", goal.kind().name());
                yaml.set(path + ".items", lines(goal.items()));
                yaml.set(path + ".amount", goal.amount());
                yaml.set(path + ".started-at", goal.startedAt());
                yaml.set(path + ".ends-at", goal.endsAt());
                goal.given().forEach((player, count) -> yaml.set(path + ".given." + player, count));
            });
        });
        if (written) {
            dirty = false;
        }
        return written;
    }

    private static List<String> lines(ItemSelection items) {
        List<String> lines = new ArrayList<>();
        items.categories().forEach(category -> lines.add(category.name().toLowerCase(java.util.Locale.ROOT)));
        lines.addAll(items.items());
        items.except().forEach(each -> lines.add("!" + each));
        return lines;
    }
}
