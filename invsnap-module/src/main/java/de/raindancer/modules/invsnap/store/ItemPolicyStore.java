package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Item policies, and the items waiting to go back to their owners — two small files, read once and kept in
 * memory, written through on every change.
 *
 * <p>The "to collect" list holds an item's only copy while it is not in anybody's inventory, so a write is
 * reported to the caller: an item whose write failed must stay where it is.
 */
public final class ItemPolicyStore {

    private final YamlStore policyFile;
    private final YamlStore returnFile;
    private final Map<String, ItemPolicy> policies = new LinkedHashMap<>();
    private final Map<UUID, List<String>> returns = new LinkedHashMap<>();

    public ItemPolicyStore(Path dataFolder) {
        this.policyFile = new YamlStore(dataFolder.resolve("item-policies.yml"));
        this.returnFile = new YamlStore(dataFolder.resolve("item-returns.yml"));
        ConfigurationSection saved = policyFile.read().getConfigurationSection("policies");
        if (saved != null) {
            for (String id : saved.getKeys(false)) {
                ConfigurationSection each = saved.getConfigurationSection(id);
                try {
                    policies.put(id, new ItemPolicy(id, UUID.fromString(each.getString("owner", "")),
                            each.getString("description", "an item"), each.getString("material", "SHIELD"),
                            each.getLong("value"), each.getLong("premium"), each.getLong("next-due"),
                            each.getString("ended", ""), each.getBoolean("told")));
                } catch (RuntimeException unreadable) {
                    // A hand-edited entry: skip it rather than lose everybody else's policy.
                }
            }
        }
        ConfigurationSection waiting = returnFile.read().getConfigurationSection("returns");
        if (waiting != null) {
            for (String owner : waiting.getKeys(false)) {
                try {
                    returns.put(UUID.fromString(owner), new ArrayList<>(waiting.getStringList(owner)));
                } catch (IllegalArgumentException notAUuid) {
                    // Same: one bad key must not lose the rest.
                }
            }
        }
    }

    // ------------------------------------------------------------------ policies

    public synchronized ItemPolicy get(String id) {
        return id == null ? null : policies.get(id);
    }

    public synchronized boolean put(ItemPolicy policy) {
        ItemPolicy before = policies.put(policy.id(), policy);
        if (savePolicies()) {
            return true;
        }
        if (before == null) {
            policies.remove(policy.id());
        } else {
            policies.put(policy.id(), before);
        }
        return false;
    }

    public synchronized void remove(String id) {
        if (policies.remove(id) != null) {
            savePolicies();
        }
    }

    public synchronized List<ItemPolicy> all() {
        return List.copyOf(policies.values());
    }

    public synchronized List<ItemPolicy> inForceOf(UUID owner) {
        return policies.values().stream().filter(each -> each.owner().equals(owner) && each.inForce()).toList();
    }

    public synchronized List<ItemPolicy> endedUntoldOf(UUID owner) {
        return policies.values().stream()
                .filter(each -> each.owner().equals(owner) && !each.inForce() && !each.told()).toList();
    }

    private boolean savePolicies() {
        List<ItemPolicy> now = List.copyOf(policies.values());
        return policyFile.write(yaml -> {
            for (ItemPolicy each : now) {
                String at = "policies." + each.id() + ".";
                yaml.set(at + "owner", each.owner().toString());
                yaml.set(at + "description", each.description());
                yaml.set(at + "material", each.material());
                yaml.set(at + "value", each.value());
                yaml.set(at + "premium", each.premium());
                yaml.set(at + "next-due", each.nextDue());
                yaml.set(at + "ended", each.ended());
                yaml.set(at + "told", each.told());
            }
        });
    }

    // ------------------------------------------------------------------ to collect

    /** The encoded items waiting for this owner, oldest first. */
    public synchronized List<String> returnsOf(UUID owner) {
        return List.copyOf(returns.getOrDefault(owner, List.of()));
    }

    /** Puts an item on the list and writes it before returning; false means it is not on the list. */
    public synchronized boolean addReturn(UUID owner, String encoded) {
        returns.computeIfAbsent(owner, key -> new ArrayList<>()).add(encoded);
        if (saveReturns()) {
            return true;
        }
        List<String> list = returns.get(owner);
        list.remove(list.size() - 1);
        return false;
    }

    /** Replaces the owner's list — what is left once some were handed over. */
    public synchronized boolean setReturns(UUID owner, List<String> encoded) {
        List<String> before = returns.get(owner);
        if (encoded.isEmpty()) {
            returns.remove(owner);
        } else {
            returns.put(owner, new ArrayList<>(encoded));
        }
        if (saveReturns()) {
            return true;
        }
        if (before == null) {
            returns.remove(owner);
        } else {
            returns.put(owner, before);
        }
        return false;
    }

    private boolean saveReturns() {
        Map<UUID, List<String>> now = new LinkedHashMap<>();
        returns.forEach((owner, list) -> now.put(owner, List.copyOf(list)));
        return returnFile.write(yaml -> now.forEach((owner, list) -> yaml.set("returns." + owner, list)));
    }
}
