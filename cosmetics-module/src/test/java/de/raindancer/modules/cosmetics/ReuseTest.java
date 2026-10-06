package de.raindancer.modules.cosmetics;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;

/** Core's reuse rules, held against this module — see {@link ReuseContract}. */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/cosmetics");
    }
}
