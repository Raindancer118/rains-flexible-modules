package de.raindancer.modules.api.discovered;

import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;

/**
 * A module whose constructor throws — the shape of a real one that reads a file it should not read
 * yet, or touches Bukkit before the server is up. Listed in the test service file on purpose.
 */
public final class ExplodingModule implements FlexModule {

    public ExplodingModule() {
        throw new IllegalStateException("read a config in its constructor");
    }

    @Override
    public ModuleInfo info() {
        return ModuleInfo.of("exploding", "Exploding", "1.0.0");
    }

    @Override
    public void enable(ModuleContext context) {
    }

    @Override
    public void disable() {
    }
}
