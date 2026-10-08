package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.moderation.model.MiningLedger;
import de.raindancer.modules.moderation.model.OreKind;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Every player's mining ledger, kept across restarts in {@code xray-ledgers.yml}. */
public final class XrayLedgers {

    private final YamlStore store;
    private final LongSupplier clock;
    private final Map<UUID, MiningLedger> ledgers = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public XrayLedgers(Path dataFolder, LongSupplier clock) {
        this.store = new YamlStore(dataFolder.resolve("xray-ledgers.yml"));
        this.clock = clock;
    }

    public MiningLedger of(UUID who, String name) {
        if (name != null) {
            names.put(who, name);
        }
        return ledgers.computeIfAbsent(who, ignored -> new MiningLedger(clock.getAsLong()));
    }

    public MiningLedger find(UUID who) {
        return ledgers.get(who);
    }

    public String nameOf(UUID who) {
        return names.get(who);
    }

    public Set<UUID> everybody() {
        return Set.copyOf(ledgers.keySet());
    }

    public void forget(UUID who) {
        ledgers.remove(who);
    }

    public void load() {
        ledgers.clear();
        ConfigurationSection root = store.read().getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                continue;
            }
            ConfigurationSection row = root.getConfigurationSection(id);
            if (row == null) {
                continue;
            }
            try {
                Map<String, Double> revealed = new HashMap<>();
                ConfigurationSection bands = row.getConfigurationSection("revealed");
                if (bands != null) {
                    for (String band : bands.getKeys(false)) {
                        revealed.put(YamlStore.fromPathPart(band), bands.getDouble(band));
                    }
                }
                Map<String, Map<OreKind, Double>> veins = new HashMap<>();
                ConfigurationSection veinBands = row.getConfigurationSection("veins");
                if (veinBands != null) {
                    for (String band : veinBands.getKeys(false)) {
                        ConfigurationSection kinds = veinBands.getConfigurationSection(band);
                        Map<OreKind, Double> counts = new EnumMap<>(OreKind.class);
                        if (kinds != null) {
                            for (String kind : kinds.getKeys(false)) {
                                counts.put(OreKind.valueOf(kind), kinds.getDouble(kind));
                            }
                        }
                        veins.put(YamlStore.fromPathPart(band), counts);
                    }
                }
                List<int[]> trail = new ArrayList<>();
                for (String step : row.getStringList("trail")) {
                    String[] parts = step.split(",");
                    if (parts.length == 4) {
                        trail.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
                    }
                }
                MiningLedger ledger = new MiningLedger(row.getLong("updated"));
                ledger.restore(revealed, veins, row.getDouble("bait-reached"), row.getDouble("bait-expected"),
                        row.getDouble("toward"), row.getDouble("away"), row.getLong("updated"),
                        row.getString("trail-world", ""), trail);
                ledgers.put(who, ledger);
                String name = row.getString("name");
                if (name != null && !name.isEmpty()) {
                    names.put(who, name);
                }
            } catch (RuntimeException malformed) {
                // One unreadable player costs that player's ledger, not everybody's.
            }
        }
    }

    public boolean flush() {
        return store.write(yaml -> ledgers.forEach((who, ledger) -> {
            String key = "players." + who;
            yaml.set(key + ".name", names.getOrDefault(who, ""));
            yaml.set(key + ".updated", ledger.updatedMillis());
            yaml.set(key + ".bait-reached", ledger.baitReached());
            yaml.set(key + ".bait-expected", ledger.baitExpected());
            yaml.set(key + ".toward", ledger.turnsToward());
            yaml.set(key + ".away", ledger.turnsAway());
            ledger.revealedByBand().forEach((band, value) -> yaml.set(key + ".revealed." + YamlStore.asPathPart(band), value));
            ledger.veinsByBand().forEach((band, kinds) -> kinds.forEach((kind, value) ->
                    yaml.set(key + ".veins." + YamlStore.asPathPart(band) + "." + kind.name(), value)));
            yaml.set(key + ".trail-world", ledger.trailWorld());
            List<String> trail = new ArrayList<>();
            for (int[] step : ledger.trail()) {
                trail.add(step[0] + "," + step[1] + "," + step[2] + "," + step[3]);
            }
            yaml.set(key + ".trail", trail);
        }));
    }
}
