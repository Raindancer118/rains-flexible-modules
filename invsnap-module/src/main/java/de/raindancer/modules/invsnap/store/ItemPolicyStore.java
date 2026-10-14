package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Item policies — the network's one document, so a policy bought on one server is in force on every other — and
 * the items waiting to go back to their owners, which live in the database ({@link ItemReturns}).
 *
 * <p>Each policy is a key of its own and a change writes that policy alone. The "to collect" list holds an
 * item's only copy while it is not in anybody's inventory, so a write is reported to the caller: an item whose
 * write failed must stay where it is.
 */
public final class ItemPolicyStore {

    private static final String SECTION = "policies";

    private final KeyedDocument<ItemPolicy> policies;
    private final ItemReturns returns;

    /** On a server of its own, and for tests: the documents as files and the list in a SQLite file beside them. */
    public ItemPolicyStore(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("item-policies.yml")),
                new YamlStore(dataFolder.resolve("item-returns.yml")),
                new ItemReturns(Database.open(dataFolder.resolve(ItemReturns.NAME + ".db"), ItemReturns.SCHEMA,
                        () -> false), System::currentTimeMillis));
    }

    /**
     * @param legacyReturns the 1.x file of waiting items, whose contents move onto the shared list
     */
    public ItemPolicyStore(YamlStore policyFile, YamlStore legacyReturns, ItemReturns returns) {
        this.policies = new KeyedDocument<>(policyFile, SECTION, ItemPolicyStore::read, ItemPolicyStore::write);
        this.returns = returns;
        policies.load();
        returns.load();
        returns.importFrom(legacyReturns);
    }

    private static ItemPolicy read(ConfigurationSection yaml, String id) {
        ConfigurationSection each = yaml.getConfigurationSection(id);
        if (each == null) {
            return null;
        }
        try {
            return new ItemPolicy(id, UUID.fromString(each.getString("owner", "")),
                    each.getString("description", "an item"), each.getString("material", "SHIELD"),
                    each.getLong("value"), each.getLong("premium"), each.getLong("next-due"),
                    each.getString("ended", ""), each.getBoolean("told"));
        } catch (RuntimeException unreadable) {
            // A hand-edited entry: skip it rather than lose everybody else's policy.
            return null;
        }
    }

    private static void write(ConfigurationSection yaml, String path, ItemPolicy each) {
        String at = path + ".";
        yaml.set(at + "owner", each.owner().toString());
        yaml.set(at + "description", each.description());
        yaml.set(at + "material", each.material());
        yaml.set(at + "value", each.value());
        yaml.set(at + "premium", each.premium());
        yaml.set(at + "next-due", each.nextDue());
        yaml.set(at + "ended", each.ended());
        yaml.set(at + "told", each.told());
    }

    public ItemReturns returns() {
        return returns;
    }

    // ------------------------------------------------------------------ policies

    public ItemPolicy get(String id) {
        return id == null ? null : policies.get(id).orElse(null);
    }

    /** Saves a policy, replacing the one of that id; false means it was not written. */
    public boolean put(ItemPolicy policy) {
        return policies.change(policy.id(), before -> policy);
    }

    public void remove(String id) {
        policies.change(id, before -> null);
    }

    public List<ItemPolicy> all() {
        return policies.all().values().stream()
                .sorted(Comparator.comparing(ItemPolicy::description).thenComparing(ItemPolicy::id)).toList();
    }

    public List<ItemPolicy> inForceOf(UUID owner) {
        return all().stream().filter(each -> each.owner().equals(owner) && each.inForce()).toList();
    }

    public List<ItemPolicy> endedUntoldOf(UUID owner) {
        return all().stream().filter(each -> each.owner().equals(owner) && !each.inForce() && !each.told()).toList();
    }

    /**
     * Moves a policy's due date on to {@code next}, but only if it still falls due at {@code expected}. Of the
     * servers that find a premium due, one wins this and takes it.
     */
    public boolean claimDue(String id, long expected, long next) {
        boolean[] won = new boolean[1];
        boolean written = policies.change(id, before -> {
            won[0] = before != null && before.inForce() && before.nextDue() == expected;
            return won[0] ? before.dueAt(next) : before;
        });
        return written && won[0];
    }

    // ------------------------------------------------------------------ to collect

    /** The encoded items waiting for this owner, oldest first. */
    public List<String> returnsOf(UUID owner) {
        return returns.of(owner).stream().map(ItemReturns.Waiting::item).toList();
    }

    /** Puts an item on the list and writes it before returning; false means it is not on the list. */
    public boolean addReturn(UUID owner, String encoded) {
        return returns.add(owner, encoded);
    }
}
