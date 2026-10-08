package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.moderation.model.OreDensity;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.model.RockBand;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** What the world's own sampled ore density is, kept in {@code ore-density.yml} so it is not relearnt on every start. */
public final class OreDensityStore {

    private final YamlStore store;
    private final OreDensity density;

    public OreDensityStore(Path dataFolder, OreDensity density) {
        this.store = new YamlStore(dataFolder.resolve("ore-density.yml"));
        this.density = density;
    }

    public OreDensity density() {
        return density;
    }

    public void load() {
        density.clear();
        ConfigurationSection bands = store.read().getConfigurationSection("bands");
        if (bands == null) {
            return;
        }
        for (String key : bands.getKeys(false)) {
            try {
                ConfigurationSection row = bands.getConfigurationSection(key);
                if (row == null) {
                    continue;
                }
                Map<OreKind, Long> ores = new EnumMap<>(OreKind.class);
                ConfigurationSection kinds = row.getConfigurationSection("ores");
                if (kinds != null) {
                    for (String kind : kinds.getKeys(false)) {
                        ores.put(OreKind.valueOf(kind), kinds.getLong(kind));
                    }
                }
                density.add(RockBand.parse(YamlStore.fromPathPart(key)), row.getLong("enclosed"), ores);
            } catch (RuntimeException malformed) {
                // A broken band is sampled again.
            }
        }
    }

    public boolean flush() {
        return store.write(yaml -> density.all().forEach((band, counts) -> {
            String key = "bands." + YamlStore.asPathPart(band);
            yaml.set(key + ".enclosed", counts.enclosed());
            for (OreKind kind : OreKind.values()) {
                long count = counts.ores(kind);
                if (count > 0) {
                    yaml.set(key + ".ores." + kind.name(), count);
                }
            }
        }));
    }
}
