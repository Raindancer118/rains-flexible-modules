package de.raindancer.modules.arena.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code settlements.yml}: stakes the survival server still has to pay out or give back — kept until it has said it
 * did, so a fight that ended while the survival server was restarting is settled when it is back, not forgotten.
 */
public final class SettlementBook {

    /** What is owed for one fight: a refund, or payouts by player. */
    public record Settlement(String fight, boolean refund, Map<String, Long> payouts) {
    }

    private final YamlStore file;
    private final Map<String, Settlement> open = new LinkedHashMap<>();

    public SettlementBook(Path file) {
        this.file = new YamlStore(file);
        YamlConfiguration yaml = this.file.read();
        ConfigurationSection section = yaml.getConfigurationSection("open");
        if (section != null) {
            for (String fight : section.getKeys(false)) {
                Map<String, Long> payouts = new LinkedHashMap<>();
                ConfigurationSection paid = section.getConfigurationSection(fight + ".payouts");
                if (paid != null) {
                    paid.getKeys(false).forEach(player -> payouts.put(player, paid.getLong(player)));
                }
                open.put(fight, new Settlement(fight, section.getBoolean(fight + ".refund"), payouts));
            }
        }
    }

    public synchronized void add(Settlement settlement) {
        open.put(settlement.fight(), settlement);
        save();
    }

    public synchronized void done(String fight) {
        if (open.remove(fight) != null) {
            save();
        }
    }

    public synchronized java.util.List<Settlement> open() {
        return java.util.List.copyOf(open.values());
    }

    private void save() {
        Map<String, Settlement> snapshot = new LinkedHashMap<>(open);
        file.write(yaml -> snapshot.values().forEach(settlement -> {
            yaml.set("open." + settlement.fight() + ".refund", settlement.refund());
            settlement.payouts().forEach((player, amount) ->
                    yaml.set("open." + settlement.fight() + ".payouts." + player, amount));
        }));
    }
}
