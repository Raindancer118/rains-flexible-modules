package de.raindancer.modules.worldutils.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.worldutils.model.ManagedWorld;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The worlds this module made, on disk, so they can be loaded again after a restart.
 *
 * <p>A file rather than a database table: a handful of lines an owner may well want to read or trim by
 * hand, and written only when a world is made or deleted. Every write goes through {@link YamlStore},
 * so a crash mid-write leaves the old file rather than half of the new one.
 */
public final class ManagedWorldStore {

    private static final LogChannel log = Log.of("worldutils");

    private final YamlStore store;
    private final List<ManagedWorld> worlds = new CopyOnWriteArrayList<>();

    public ManagedWorldStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("worlds.yml"));
    }

    /** Reads the file. A missing one is a server that has not made a world yet. */
    public List<ManagedWorld> load() {
        worlds.clear();
        YamlConfiguration yaml = store.read();
        ConfigurationSection section = yaml.getConfigurationSection("worlds");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                String environment = section.getString(name + ".environment", "NORMAL");
                try {
                    worlds.add(new ManagedWorld(name,
                            World.Environment.valueOf(environment.toUpperCase(Locale.ROOT))));
                } catch (IllegalArgumentException notAnEnvironment) {
                    // Loading it as the wrong dimension would be worse than not loading it: say so.
                    log.warn("'{}' in worlds.yml has environment '{}', which is not one; it is not loaded.",
                            name, environment);
                }
            }
        }
        return all();
    }

    public List<ManagedWorld> all() {
        return List.copyOf(worlds);
    }

    public Optional<ManagedWorld> named(String name) {
        return worlds.stream().filter(world -> world.isCalled(name)).findFirst();
    }

    public boolean isManaged(String name) {
        return named(name).isPresent();
    }

    /** Remembers a world. @return whether that reached the disk */
    public boolean add(ManagedWorld world) {
        if (world == null) {
            return false;
        }
        worlds.removeIf(existing -> existing.isCalled(world.name()));
        worlds.add(world);
        return save();
    }

    /** Forgets a world. @return whether that reached the disk */
    public boolean remove(String name) {
        worlds.removeIf(existing -> existing.isCalled(name));
        return save();
    }

    private boolean save() {
        List<ManagedWorld> snapshot = new ArrayList<>(worlds);
        return store.write(yaml -> {
            for (ManagedWorld world : snapshot) {
                yaml.set("worlds." + world.name() + ".environment", world.environment().name());
            }
        });
    }
}
