package de.raindancer.modules.mannequin.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One mannequin, one file — {@code <dataFolder>/mannequins/<id>.yml}.
 *
 * <h2>Why one file per mannequin rather than one file for all of them</h2>
 * A server with a training room might have a dozen of these, not the thousands a location pool can
 * hold — and a mannequin is edited far more often, one at a time, through the loadout and skin
 * screens. A single shared file would mean every equip click rewrites everybody else's dummy too.
 *
 * <h2>Every write is atomic</h2>
 * Through {@link YamlStore}: write-to-a-temporary-then-move, so a server killed mid-save leaves the
 * old file or the new one and never half of either.
 */
public final class MannequinStore {

    private static final LogChannel log = Log.of("mannequin");

    private final Path folder;

    public MannequinStore(Path dataFolder) {
        this.folder = dataFolder.resolve("mannequins");
        try {
            Files.createDirectories(folder);
        } catch (IOException cannot) {
            throw new UncheckedIOException("could not create " + folder, cannot);
        }
    }

    public Path folder() {
        return folder;
    }

    private YamlStore storeFor(String id) {
        return new YamlStore(folder.resolve(id + ".yml"));
    }

    /** Writes one mannequin's file. @return whether it reached the disk */
    public boolean save(Mannequin mannequin) {
        return storeFor(mannequin.id()).write(yaml -> {
            yaml.set("id", mannequin.id());
            yaml.set("owner", mannequin.owner().toString());
            yaml.set("world", mannequin.world());
            yaml.set("x", mannequin.x());
            yaml.set("y", mannequin.y());
            yaml.set("z", mannequin.z());
            yaml.set("display-name", mannequin.displayName());
            yaml.set("skin-source", mannequin.skinSource() == null ? null
                    : mannequin.skinSource().toString());
            yaml.set("blocks-with-shield", mannequin.blocksWithShield());
            yaml.set("emits-redstone-signal", mannequin.emitsRedstoneSignal());
            yaml.set("max-health-override", mannequin.maxHealthOverride());
            for (Map.Entry<EquipmentSlot, ItemSpec> entry : mannequin.loadout().entrySet()) {
                String at = "loadout." + entry.getKey().name();
                yaml.set(at + ".material", entry.getValue().material().name());
                List<String> enchants = new ArrayList<>();
                for (Map.Entry<Enchantment, Integer> enchant : entry.getValue().enchants().entrySet()) {
                    enchants.add(enchant.getKey().getKey().asString() + ":" + enchant.getValue());
                }
                yaml.set(at + ".enchants", enchants);
            }
        });
    }

    /** Deletes one mannequin's file. @return whether there was one to delete */
    public boolean delete(String id) {
        Path file = folder.resolve(id + ".yml");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException cannot) {
            log.error(cannot, "could not delete {}", file);
            return false;
        }
    }

    /**
     * Every mannequin on disk. An entry that will not read is skipped and named, and the rest still
     * load — one mangled file must not cost the server every other mannequin already placed.
     */
    public List<Mannequin> loadAll() {
        List<Mannequin> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                try {
                    Mannequin mannequin = read(file);
                    if (mannequin != null) {
                        found.add(mannequin);
                    }
                } catch (RuntimeException broken) {
                    unreadable.add(file.getFileName().toString());
                }
            }
        } catch (IOException unreadableDir) {
            log.error(unreadableDir, "could not list {}", folder);
        }
        if (!unreadable.isEmpty()) {
            log.error("{} mannequin file(s) could not be read and have been skipped: {}",
                    unreadable.size(), String.join(", ", unreadable));
        }
        return found;
    }

    private Mannequin read(Path file) {
        YamlConfiguration yaml = new YamlStore(file).read();
        if (!yaml.contains("id")) {
            return null;
        }
        String id = required(yaml.getString("id"), "id");
        UUID owner = UUID.fromString(required(yaml.getString("owner"), "owner"));
        String world = required(yaml.getString("world"), "world");
        int x = yaml.getInt("x");
        int y = yaml.getInt("y");
        int z = yaml.getInt("z");
        String displayName = yaml.getString("display-name", "Mannequin");
        String skinRaw = yaml.getString("skin-source");
        UUID skinSource = skinRaw == null || skinRaw.isBlank() ? null : UUID.fromString(skinRaw);
        boolean blocksWithShield = yaml.getBoolean("blocks-with-shield", true);
        boolean emitsRedstoneSignal = yaml.getBoolean("emits-redstone-signal", false);
        Double maxHealthOverride = yaml.contains("max-health-override") && yaml.get("max-health-override") != null
                ? yaml.getDouble("max-health-override") : null;

        Map<EquipmentSlot, ItemSpec> loadout = new LinkedHashMap<>();
        ConfigurationSection loadoutSection = yaml.getConfigurationSection("loadout");
        if (loadoutSection != null) {
            for (String slotName : loadoutSection.getKeys(false)) {
                EquipmentSlot slot;
                try {
                    slot = EquipmentSlot.valueOf(slotName);
                } catch (IllegalArgumentException notASlot) {
                    continue;
                }
                ConfigurationSection entry = loadoutSection.getConfigurationSection(slotName);
                if (entry == null) {
                    continue;
                }
                Material material = Material.matchMaterial(entry.getString("material", ""));
                if (material == null) {
                    continue;
                }
                Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
                for (String encoded : entry.getStringList("enchants")) {
                    int colon = encoded.lastIndexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    NamespacedKey key = NamespacedKey.fromString(encoded.substring(0, colon));
                    Enchantment enchant = key == null ? null : Registry.ENCHANTMENT.get(key);
                    if (enchant == null) {
                        continue;
                    }
                    try {
                        enchants.put(enchant, Integer.parseInt(encoded.substring(colon + 1)));
                    } catch (NumberFormatException notANumber) {
                        // Skipped, same as an unreadable used-by entry elsewhere in this codebase.
                    }
                }
                loadout.put(slot, new ItemSpec(material, enchants));
            }
        }

        return new Mannequin(id, owner, world, x, y, z, displayName, loadout, skinSource,
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride);
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a mannequin file with no " + what);
        }
        return value;
    }
}
