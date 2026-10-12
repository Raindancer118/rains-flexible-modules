package de.raindancer.modules.guilds;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;

/** That the module rebuilds nothing RainsCore already has. See {@code ReuseContract}. */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/guilds");
    }
}
