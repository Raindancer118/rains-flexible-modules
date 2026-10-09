package de.raindancer.modules.claims.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.claims.model.UpkeepAccount;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Who is billed when and who owes what, in {@code upkeep.yml} beside the claims. */
public final class UpkeepStore {

    private static final LogChannel log = Log.of("claims");
    private static final String OWNERS = "owners";

    private final YamlStore store;

    public UpkeepStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("upkeep.yml"));
    }

    public Map<UUID, UpkeepAccount> load() {
        Map<UUID, UpkeepAccount> accounts = new HashMap<>();
        if (!store.exists()) {
            return accounts;
        }
        ConfigurationSection owners = store.read().getConfigurationSection(OWNERS);
        if (!store.problems().isEmpty()) {
            // Set aside rather than overwritten: it holds what people owe, which is not ours to lose.
            store.quarantine();
            return accounts;
        }
        if (owners == null) {
            return accounts;
        }
        for (String key : owners.getKeys(false)) {
            ConfigurationSection each = owners.getConfigurationSection(key);
            UUID who;
            try {
                who = UUID.fromString(key);
            } catch (IllegalArgumentException notAnId) {
                log.warn("upkeep.yml: '{}' is not a player id; skipping it.", key);
                continue;
            }
            if (each != null) {
                accounts.put(who, new UpkeepAccount(each.getLong("next-due"),
                        Math.max(0L, each.getLong("owed")), Math.max(0L, each.getLong("since"))));
            }
        }
        return accounts;
    }

    /** @return whether it reached disk */
    public boolean save(Map<UUID, UpkeepAccount> accounts) {
        return store.write(yaml -> {
            yaml.options().setHeader(List.of(
                    "Claim upkeep: when each owner is billed next, and what they owe.",
                    "Written by the plugin; 'owed' is in the currency's smallest unit."));
            accounts.forEach((who, account) -> {
                String base = OWNERS + "." + who;
                yaml.set(base + ".next-due", account.nextDue());
                yaml.set(base + ".owed", account.owed());
                yaml.set(base + ".since", account.since());
            });
        });
    }
}
