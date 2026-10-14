package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.moderation.model.FineRecord;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every fine and what is still owed on it, in {@code fines.yml}.
 *
 * <p>Written at once on every change, like {@link RuleOffences}: a debt payment or a refund that is lost to a
 * crash is money that moved with no record of it.
 */
public final class FineLedger {

    private final YamlStore file;
    private final Map<String, FineRecord> records = new LinkedHashMap<>();
    private final DirtyKeys dirty = new DirtyKeys();
    private final NetworkBus.Subscription following;

    /** One ledger for the whole network: a fine given on one server is owed on every other. */
    public FineLedger(Path file) {
        this(YamlStore.network(file));
    }

    FineLedger(YamlStore file) {
        this.file = file;
        this.following = file.onChange(this::reload);
    }

    public void close() {
        following.close();
    }

    public synchronized void load() {
        file.readIfReadable().ifPresent(this::adopt);
    }

    /** Another server changed the ledger: what this server changed goes out first, then theirs comes in. */
    private synchronized void reload() {
        if (save()) {
            load();
        }
    }

    private void adopt(YamlConfiguration yaml) {
        Map<String, FineRecord> read = new LinkedHashMap<>();
        ConfigurationSection fines = yaml.getConfigurationSection("fines");
        if (fines == null) {
            fines = new YamlConfiguration();
        }
        for (String id : fines.getKeys(false)) {
            ConfigurationSection at = fines.getConfigurationSection(id);
            if (at == null) {
                continue;
            }
            try {
                String victim = at.getString("victim", "");
                read.put(id, new FineRecord(id, UUID.fromString(at.getString("target", "")),
                        at.getString("punishment", ""), at.getString("source", "fine"),
                        at.getString("reason", ""), at.getLong("given-at"), at.getLong("charged"),
                        at.getLong("paid"), at.getLong("to-victim"),
                        victim.isEmpty() ? null : UUID.fromString(victim), at.getLong("debt"),
                        at.getLong("forgiven"), at.getBoolean("revoked"), at.getLong("refunded")));
            } catch (IllegalArgumentException handEdited) {
                // Skipped: a fine nobody can attribute to a player cannot be collected from anybody.
            }
        }
        // A fine this server changed and could not write yet keeps this server's word.
        records.keySet().removeIf(id -> !read.containsKey(id) && !dirty.has(id));
        read.forEach((id, record) -> {
            if (!dirty.has(id)) {
                records.put(id, record);
            }
        });
    }

    public synchronized boolean add(FineRecord record) {
        records.put(record.id(), record);
        dirty.mark(record.id());
        return save();
    }

    public synchronized Optional<FineRecord> get(String id) {
        return Optional.ofNullable(records.get(id));
    }

    /** Newest first. */
    public synchronized List<FineRecord> of(UUID player) {
        return records.values().stream().filter(each -> each.target().equals(player))
                .sorted(Comparator.comparingLong(FineRecord::givenAt).reversed()).toList();
    }

    public synchronized List<FineRecord> forPunishment(String punishmentId) {
        return records.values().stream().filter(each -> each.punishmentId().equals(punishmentId)).toList();
    }

    /** What they owe across every open fine, in minor units. */
    public synchronized long owed(UUID player) {
        return records.values().stream().filter(each -> each.target().equals(player) && !each.revoked())
                .mapToLong(FineRecord::debt).sum();
    }

    /** Settles {@code amount} against their oldest debts first; returns what was actually settled. */
    public synchronized long payDown(UUID player, long amount) {
        long left = Math.max(0, amount);
        List<FineRecord> open = new ArrayList<>(records.values().stream()
                .filter(each -> each.target().equals(player) && each.isOpen())
                .sorted(Comparator.comparingLong(FineRecord::givenAt)).toList());
        long settled = 0;
        for (FineRecord each : open) {
            if (left <= 0) {
                break;
            }
            FineRecord after = each.paidDown(left);
            long took = after.paid() - each.paid();
            records.put(each.id(), after);
            dirty.mark(each.id());
            left -= took;
            settled += took;
        }
        if (settled > 0) {
            save();
        }
        return settled;
    }

    /** Writes off everything they owe; returns how much that was. */
    public synchronized long forgive(UUID player) {
        long wiped = 0;
        for (FineRecord each : new ArrayList<>(records.values())) {
            if (each.target().equals(player) && each.isOpen()) {
                wiped += each.debt();
                records.put(each.id(), each.forgivenDebt());
                dirty.mark(each.id());
            }
        }
        if (wiped > 0) {
            save();
        }
        return wiped;
    }

    public synchronized boolean replace(FineRecord record) {
        records.put(record.id(), record);
        dirty.mark(record.id());
        return save();
    }

    /** Writes the fines this server changed, and only those. */
    private boolean save() {
        return dirty.write(file, (yaml, id) -> {
            FineRecord each = records.get(id);
            String at = "fines." + id;
            if (each == null) {
                yaml.set(at, null);
                return;
            }
            at += ".";
            yaml.set(at + "target", each.target().toString());
            yaml.set(at + "punishment", each.punishmentId());
            yaml.set(at + "source", each.source());
            yaml.set(at + "reason", each.reason());
            yaml.set(at + "given-at", each.givenAt());
            yaml.set(at + "charged", each.charged());
            yaml.set(at + "paid", each.paid());
            yaml.set(at + "to-victim", each.toVictim());
            yaml.set(at + "victim", each.victim() == null ? "" : each.victim().toString());
            yaml.set(at + "debt", each.debt());
            yaml.set(at + "forgiven", each.forgiven());
            yaml.set(at + "revoked", each.revoked());
            yaml.set(at + "refunded", each.refunded());
        });
    }
}
