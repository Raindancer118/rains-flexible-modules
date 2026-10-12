package de.raindancer.modules.arena.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.arena.model.Kit;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The fingerprint of each built-in kit as it was last seeded, in {@code shipped-kits.yml} — so a kit whose stored copy
 * still has that fingerprint is known to be untouched, and may follow a newer version of the plugin.
 */
public final class ShippedKits {

    private final YamlStore store;

    public ShippedKits(Path file) {
        this.store = new YamlStore(file);
    }

    public static String fingerprint(Kit kit) {
        return Integer.toHexString((String.join("\n", kit.items()) + "|" + String.join("\n", kit.effects())
                + "|" + kit.harmless()).hashCode());
    }

    public Optional<String> seeded(String id) {
        return Optional.ofNullable(store.read().getString("kits." + YamlStore.asPathPart(id)));
    }

    public void remember(Kit kit) {
        store.update(yaml -> yaml.set("kits." + YamlStore.asPathPart(kit.id()), fingerprint(kit)));
    }
}
