package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
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
 */
public final class RuleOffences {

    private final YamlStore file;
    private final Map<UUID, Map<String, Integer>> counts = new ConcurrentHashMap<>();

    public RuleOffences(Path file) {
        this.file = new YamlStore(file);
    }

    public void load() {
        YamlConfiguration yaml = file.read();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
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
                counts.put(UUID.fromString(player), theirs);
            } catch (IllegalArgumentException notAnId) {
                // Hand-edited; skipped.
            }
        }
    }

    public int count(UUID player, String ruleId) {
        return counts.getOrDefault(player, Map.of()).getOrDefault(ruleId, 0);
    }

    /** One more, written at once: an offence that was lost to a crash would make the next one a first again. */
    public synchronized boolean add(UUID player, String ruleId) {
        counts.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).merge(ruleId, 1, Integer::sum);
        Map<UUID, Map<String, Integer>> snapshot = new HashMap<>();
        counts.forEach((id, theirs) -> snapshot.put(id, new HashMap<>(theirs)));
        return file.write(yaml -> snapshot.forEach((id, theirs) -> theirs.forEach((rule, count) ->
                yaml.set("players." + id + "." + YamlStore.asPathPart(rule), count))));
    }
}
