package de.raindancer.modules.manhunt.setup;

import de.raindancer.core.data.store.YamlStore;

import java.nio.file.Path;

/**
 * Whether this server has been through the setup wizard — {@code setup.yml}, so an admin is offered
 * it once, not on every login for ever.
 */
public final class SetupState {

    private static final String DONE = "done";

    private final YamlStore store;
    private volatile Boolean done;

    public SetupState(Path file) {
        this.store = new YamlStore(file);
    }

    public boolean done() {
        Boolean known = done;
        if (known == null) {
            known = store.exists() && store.read().getBoolean(DONE, false);
            done = known;
        }
        return known;
    }

    public void markDone() {
        done = true;
        store.write(yaml -> yaml.set(DONE, true));
    }
}
