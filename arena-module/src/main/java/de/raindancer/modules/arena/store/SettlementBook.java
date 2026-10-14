package de.raindancer.modules.arena.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code settlements.yml}: stakes a survival server still has to pay out or give back — kept until it has said it
 * did, so a fight that ended while it was restarting is settled when it is back, not forgotten. One entry per fight
 * and survival server: a fight between players of two servers is held, and so settled, by each of them.
 */
public final class SettlementBook {

    /**
     * What is owed to one survival server for one fight: a refund, or payouts by player.
     *
     * @param home         the survival server's id; empty for the one a link has (and for entries written before
     *                     there were several)
     * @param participants everybody of the fight, as the server may pay one who staked elsewhere; empty to leave it
     *                     to the server
     */
    public record Settlement(String fight, String home, boolean refund, Map<String, Long> payouts, String participants) {

        public Settlement(String fight, boolean refund, Map<String, Long> payouts) {
            this(fight, "", refund, payouts, "");
        }

        String key() {
            return keyOf(fight, home);
        }
    }

    private final YamlStore file;
    private final Map<String, Settlement> open = new LinkedHashMap<>();

    public SettlementBook(YamlStore file) {
        this.file = file;
        YamlConfiguration yaml = this.file.read();
        ConfigurationSection section = yaml.getConfigurationSection("open");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                Map<String, Long> payouts = new LinkedHashMap<>();
                ConfigurationSection paid = entry.getConfigurationSection("payouts");
                if (paid != null) {
                    paid.getKeys(false).forEach(player -> payouts.put(player, paid.getLong(player)));
                }
                Settlement settlement = new Settlement(entry.getString("fight", key), entry.getString("home", ""),
                        entry.getBoolean("refund"), payouts, entry.getString("participants", ""));
                open.put(settlement.key(), settlement);
            }
        }
    }

    public SettlementBook(Path file) {
        this(new YamlStore(file));
    }

    public synchronized void add(Settlement settlement) {
        open.put(settlement.key(), settlement);
        save();
    }

    public synchronized void done(String fight, String home) {
        if (open.remove(keyOf(fight, home)) != null) {
            save();
        }
    }

    public synchronized List<Settlement> open() {
        return List.copyOf(open.values());
    }

    private static String keyOf(String fight, String home) {
        return home == null || home.isEmpty() ? fight : fight + "@" + home;
    }

    private void save() {
        Map<String, Settlement> snapshot = new LinkedHashMap<>(open);
        file.write(yaml -> snapshot.values().forEach(settlement -> {
            String path = "open." + YamlStore.asPathPart(settlement.key());
            yaml.set(path + ".fight", settlement.fight());
            yaml.set(path + ".home", settlement.home());
            yaml.set(path + ".refund", settlement.refund());
            yaml.set(path + ".participants", settlement.participants());
            settlement.payouts().forEach((player, amount) -> yaml.set(path + ".payouts." + player, amount));
        }));
    }
}
