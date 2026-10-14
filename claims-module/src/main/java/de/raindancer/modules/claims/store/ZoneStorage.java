package de.raindancer.modules.claims.store;

import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.NoClaimZone;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import de.raindancer.core.data.store.YamlStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

/** Single-document storage for the no-claim zones; there are never many of them. */
public final class ZoneStorage {

    private static final LogChannel log = Log.of("land");

    private final YamlStore store;

    public ZoneStorage(Path dataFolder) {
        this(new YamlStore(dataFolder.resolve("no-claim-zones.yml")));
    }

    public ZoneStorage(YamlStore store) {
        this.store = store;
    }

    public List<NoClaimZone> loadAll() {
        List<NoClaimZone> zones = new ArrayList<>();
        if (!store.exists()) {
            return zones;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            // Not written over: the next save sets the unreadable document aside first.
            log.error("The no-claim zones could not be read ({}) — no zone is being enforced until this is fixed.",
                    String.join("; ", store.problems()));
            return zones;
        }
        ConfigurationSection root = yaml.getConfigurationSection("zones");
        if (root == null) {
            return zones;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            try {
                List<ClaimPoint> vertices = new ArrayList<>();
                for (String raw : section.getStringList("vertices")) {
                    vertices.add(ClaimPoint.deserialize(raw));
                }
                if (vertices.size() < 3) {
                    log.warn("No-claim zone '" + name + "' has a degenerate shape — skipping.");
                    continue;
                }
                ClaimShape shape = new ClaimShape(vertices, section.getInt("min-y"), section.getInt("max-y"));
                zones.add(new NoClaimZone(name,
                        UUID.fromString(section.getString("world-id", UUID.randomUUID().toString())),
                        section.getString("world-name", "unknown"),
                        shape,
                        section.getLong("created-at", System.currentTimeMillis())));
            } catch (RuntimeException exception) {
                log.error(exception, "Could not load no-claim zone '" + name + "'");
            }
        }
        return zones;
    }

    public void saveAll(Collection<NoClaimZone> zones) throws IOException {
        boolean written = store.write(yaml -> fill(yaml, zones));
        if (!written) {
            throw new IOException("Could not write the no-claim zones");
        }
    }

    private static void fill(YamlConfiguration yaml, Collection<NoClaimZone> zones) {
        for (NoClaimZone zone : zones) {
            String base = "zones." + zone.name();
            List<String> vertices = new ArrayList<>();
            for (ClaimPoint point : zone.shape().vertices()) {
                vertices.add(point.serialize());
            }
            yaml.set(base + ".world-id", zone.worldId().toString());
            yaml.set(base + ".world-name", zone.worldName());
            yaml.set(base + ".vertices", vertices);
            yaml.set(base + ".min-y", zone.shape().minY());
            yaml.set(base + ".max-y", zone.shape().maxY());
            yaml.set(base + ".created-at", zone.createdAt());
        }
    }
}
