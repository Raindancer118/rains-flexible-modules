package de.raindancer.modules.anticheat;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;

/** Nothing here is a second copy of something RainsCore owns. */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/anticheat");
    }
}
