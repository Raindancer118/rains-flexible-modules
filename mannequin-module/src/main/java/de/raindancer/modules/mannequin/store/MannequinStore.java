package de.raindancer.modules.mannequin.store;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.RegistryAccess;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One mannequin, one document — {@code mannequins/<id>.yml} in a {@link DocumentFolder}: a row in Core's
 * database, or a file of that name without Core; a 1.x file still lying there is brought in when first read.
 * A mannequin stands in this server's world, so it stays per server.
 *
 * <h2>Why one file per mannequin rather than one file for all of them</h2>
 * A server with a training room might have a dozen of these, not the thousands a location pool can
 * hold — and a mannequin is edited far more often, one at a time, through the loadout and skin
 * screens. A single shared file would mean every equip click rewrites everybody else's dummy too.
 *
 * <h2>Every write is whole</h2>
 * Through {@link YamlStore}: a server killed mid-save leaves the old document or the new one and never
 * half of either.
 */
public final class MannequinStore {

    private static final LogChannel log = Log.of("mannequin");

    private static final String SUFFIX = ".yml";

    private final DocumentFolder folder;

    public MannequinStore(Path dataFolder) {
        this(DocumentFolder.of(dataFolder.resolve("mannequins")));
    }

    public MannequinStore(DocumentFolder folder) {
        this.folder = folder;
    }

    private YamlStore storeFor(String id) {
        return folder.store(id + SUFFIX);
    }

    /** Writes one mannequin. @return whether it was stored */
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
            yaml.set("kind", mannequin.kind().name());
            yaml.set("yaw", (double) mannequin.yaw());
            yaml.set("trusted", mannequin.trusted().stream().map(UUID::toString).toList());
            yaml.set("claim-id", mannequin.claimId() == null ? null : mannequin.claimId().toString());
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

    /** Deletes one mannequin. @return whether it is gone */
    public boolean delete(String id) {
        return folder.delete(id + SUFFIX);
    }

    /**
     * Every mannequin stored. An entry that will not read is skipped and named, and the rest still
     * load — one mangled document must not cost the server every other mannequin already placed.
     */
    public List<Mannequin> loadAll() {
        List<Mannequin> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String name : folder.names(SUFFIX)) {
            try {
                Mannequin mannequin = read(folder.store(name));
                if (mannequin != null) {
                    found.add(mannequin);
                }
            } catch (RuntimeException broken) {
                unreadable.add(name);
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} mannequin(s) could not be read and have been skipped, left as they are: {}",
                    unreadable.size(), String.join(", ", unreadable));
        }
        return found;
    }

    private Mannequin read(YamlStore store) {
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            throw new IllegalStateException(String.join("; ", store.problems()));
        }
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
        // Missing (a file from before kinds existed) or unreadable (a typo, a hand edit) both fall
        // back to PLAYER rather than throwing — the same "read old data safely" discipline every
        // other optional field in this file already follows.
        MannequinKind kind = MannequinKind.byName(yaml.getString("kind")).orElse(MannequinKind.PLAYER);
        // Missing (a file from before facing was captured) defaults to 0 — vanilla's own "due
        // south" — rather than throwing.
        float yaw = (float) yaml.getDouble("yaw", 0.0);

        Set<UUID> trusted = new LinkedHashSet<>();
        for (String raw : yaml.getStringList("trusted")) {
            try {
                trusted.add(UUID.fromString(raw));
            } catch (IllegalArgumentException notAUuid) {
                // Skipped, same discipline as an unreadable enchant entry above.
            }
        }

        String claimIdRaw = yaml.getString("claim-id");
        UUID claimId = null;
        if (claimIdRaw != null && !claimIdRaw.isBlank()) {
            try {
                claimId = UUID.fromString(claimIdRaw);
            } catch (IllegalArgumentException notAUuid) {
                // A hand edit gone wrong reads as "belongs to no claim" rather than refusing to load
                // the whole mannequin over one stray field.
            }
        }

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
                    Enchantment enchant = key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
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
                blocksWithShield, emitsRedstoneSignal, maxHealthOverride, kind, yaw, trusted, claimId);
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a mannequin file with no " + what);
        }
        return value;
    }
}
