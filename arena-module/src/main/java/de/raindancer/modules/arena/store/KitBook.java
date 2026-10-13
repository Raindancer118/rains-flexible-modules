package de.raindancer.modules.arena.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.arena.model.Kit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code kits.yml}: every kit, in the order they were made. Held in memory, written on every change. */
public final class KitBook {

    private static final LogChannel log = Log.of("arena");

    private final YamlStore file;
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    public KitBook(Path file) {
        this.file = new YamlStore(file);
        load();
    }

    public synchronized List<Kit> all() {
        return List.copyOf(kits.values());
    }

    public synchronized Optional<Kit> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(kits.get(id.toLowerCase(java.util.Locale.ROOT)));
    }

    public synchronized boolean has(String id) {
        return get(id).isPresent();
    }

    /** Adds or replaces. @return whether it reached disk */
    public synchronized boolean put(Kit kit) {
        kits.put(kit.id(), kit);
        return save();
    }

    /** Only adds — what the defaults use, so an owner's own version of a kit is never overwritten. */
    public synchronized boolean putIfAbsent(Kit kit) {
        if (kits.containsKey(kit.id())) {
            return true;
        }
        kits.put(kit.id(), kit);
        return save();
    }

    public synchronized boolean remove(String id) {
        if (kits.remove(id.toLowerCase(java.util.Locale.ROOT)) == null) {
            return false;
        }
        return save();
    }

    private boolean save() {
        List<Kit> snapshot = new ArrayList<>(kits.values());
        return file.write(yaml -> {
            for (Kit kit : snapshot) {
                String at = "kits." + YamlStore.asPathPart(kit.id());
                yaml.set(at + ".name", kit.name());
                yaml.set(at + ".icon", kit.icon());
                yaml.set(at + ".harmless", kit.harmless());
                yaml.set(at + ".building", kit.building());
                yaml.set(at + ".effects", kit.effects());
                yaml.set(at + ".items", kit.items());
            }
        });
    }

    private synchronized void load() {
        YamlConfiguration yaml = file.read();
        if (!file.problems().isEmpty()) {
            log.error("kits.yml could not be read: {}. The arena has no kits until it is fixed.", file.problems());
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("kits");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection kit = section.getConfigurationSection(key);
                kits.put(YamlStore.fromPathPart(key), new Kit(YamlStore.fromPathPart(key), kit.getString("name"),
                        kit.getString("icon"), kit.getStringList("items"), kit.getStringList("effects"),
                        kit.getBoolean("harmless"), kit.getBoolean("building")));
            } catch (RuntimeException broken) {
                log.error("Kit '{}' in kits.yml is skipped: {}", key, broken.getMessage());
            }
        }
    }
}
