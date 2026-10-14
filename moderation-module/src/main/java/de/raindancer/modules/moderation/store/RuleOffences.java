package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often each player broke each of the server's rules — counted by the rule's id, so renaming a rule or
 * moving it up the list keeps everybody's count. Lifted punishments still count, as they do for the reason
 * ladders: an appeal that succeeded still leaves the offence.
 *
 * <p>The count is the network's: a rule broken on one server is a rule broken. Two servers counting the same
 * offence at once must both land, so what is written is "one more", added to whatever the database holds.
 */
public final class RuleOffences {

    private final YamlStore file;
    private final Map<UUID, Map<String, Integer>> counts = new ConcurrentHashMap<>();
    /** Offences counted here and not yet written, per player and rule. */
    private final Map<UUID, Map<String, Integer>> unwritten = new HashMap<>();
    private final NetworkBus.Subscription following;

    public RuleOffences(Path file) {
        this(YamlStore.network(file));
    }

    RuleOffences(YamlStore file) {
        this.file = file;
        this.following = file.onChange(this::reload);
    }

    public void close() {
        following.close();
    }

    public synchronized void load() {
        file.readIfReadable().ifPresent(this::adopt);
    }

    /** Another server counted: what this server counted goes out first, then the totals come in. */
    private synchronized void reload() {
        if (write()) {
            load();
        }
    }

    private void adopt(YamlConfiguration yaml) {
        Map<UUID, Map<String, Integer>> read = new HashMap<>();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String player : players.getKeys(false)) {
                ConfigurationSection rules = players.getConfigurationSection(player);
                if (rules == null) {
                    continue;
                }
                try {
                    Map<String, Integer> theirs = new ConcurrentHashMap<>();
                    for (String rule : rules.getKeys(false)) {
                        theirs.put(YamlStore.fromPathPart(rule), rules.getInt(rule));
                    }
                    read.put(UUID.fromString(player), theirs);
                } catch (IllegalArgumentException notAnId) {
                    // Hand-edited; skipped.
                }
            }
        }
        counts.keySet().removeIf(who -> !read.containsKey(who));
        read.forEach((who, theirs) -> {
            Map<String, Integer> merged = new ConcurrentHashMap<>(theirs);
            // Counted here and not written yet: still counted.
            unwritten.getOrDefault(who, Map.of()).forEach((rule, more) -> merged.merge(rule, more, Integer::sum));
            counts.put(who, merged);
        });
        unwritten.forEach((who, rules) -> rules.forEach((rule, more) ->
                counts.computeIfAbsent(who, id -> new ConcurrentHashMap<>()).putIfAbsent(rule, more)));
    }

    public int count(UUID player, String ruleId) {
        return counts.getOrDefault(player, Map.of()).getOrDefault(ruleId, 0);
    }

    /** One more, written at once: an offence that was lost to a crash would make the next one a first again. */
    public synchronized boolean add(UUID player, String ruleId) {
        counts.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).merge(ruleId, 1, Integer::sum);
        unwritten.computeIfAbsent(player, id -> new HashMap<>()).merge(ruleId, 1, Integer::sum);
        return write();
    }

    /** Adds what is unwritten onto what the database holds, and takes the totals it now holds as the count. */
    private boolean write() {
        if (unwritten.isEmpty()) {
            return true;
        }
        Map<UUID, Map<String, Integer>> totals = new HashMap<>();
        boolean written = file.update(yaml -> {
            totals.clear();
            unwritten.forEach((player, rules) -> rules.forEach((rule, more) -> {
                String at = "players." + player + "." + YamlStore.asPathPart(rule);
                int total = yaml.getInt(at) + more;
                yaml.set(at, total);
                totals.computeIfAbsent(player, id -> new HashMap<>()).put(rule, total);
            }));
        });
        if (!written) {
            return false;
        }
        unwritten.clear();
        totals.forEach((player, rules) -> counts.computeIfAbsent(player, id -> new ConcurrentHashMap<>())
                .putAll(rules));
        return true;
    }
}
