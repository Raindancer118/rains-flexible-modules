package de.raindancer.modules.api.discovered;

import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;

/** A module that a {@code ServiceLoader} can actually build. Listed in the test service file. */
public final class GoodModule implements FlexModule {

    @Override
    public ModuleInfo info() {
        return ModuleInfo.of("good", "Good", "1.0.0");
    }

    @Override
    public void enable(ModuleContext context) {
    }

    @Override
    public void disable() {
    }
}
