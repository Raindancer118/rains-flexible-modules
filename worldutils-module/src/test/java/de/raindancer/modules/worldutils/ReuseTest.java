package de.raindancer.modules.worldutils;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;

/** That nothing here is a second copy of something RainsCore already owns. */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/worldutils");
    }
}
