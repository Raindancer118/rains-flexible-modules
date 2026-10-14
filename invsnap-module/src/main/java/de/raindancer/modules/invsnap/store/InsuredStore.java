package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Who has opted in to death insurance — the network's one document, so opting in on one server insures a death
 * on another. Each player is a key of their own: a change writes that player alone.
 *
 * <p>1.x kept one {@code players} list; it is turned into keys on first use.
 */
public final class InsuredStore {

    private static final String SECTION = "insured";
    private static final String LEGACY_LIST = "players";

    private final YamlStore file;
    private final KeyedDocument<Boolean> insured;

    public InsuredStore(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("insured.yml")));
    }

    public InsuredStore(YamlStore file) {
        this.file = file;
        this.insured = new KeyedDocument<>(file, SECTION,
                (yaml, key) -> yaml.getBoolean(key) ? Boolean.TRUE : null,
                (yaml, path, on) -> yaml.set(path, true));
        insured.load();
        migrateList();
    }

    private void migrateList() {
        List<String> old = file.readIfReadable().map(yaml -> yaml.getStringList(LEGACY_LIST)).orElse(List.of());
        if (old.isEmpty()) {
            return;
        }
        boolean all = true;
        for (String raw : old) {
            try {
                all &= insured.change(UUID.fromString(raw).toString(), before -> Boolean.TRUE);
            } catch (IllegalArgumentException notAUuid) {
                // A hand-edited line: skip it rather than lose everybody else's choice.
            }
        }
        if (all) {
            file.update(yaml -> yaml.set(LEGACY_LIST, null));
        }
    }

    public boolean isInsured(UUID player) {
        return insured.get(player.toString()).isPresent();
    }

    /** @return whether the choice is saved */
    public boolean set(UUID player, boolean on) {
        return insured.change(player.toString(), before -> on ? Boolean.TRUE : null);
    }
}
