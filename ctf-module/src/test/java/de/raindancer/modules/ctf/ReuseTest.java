package de.raindancer.modules.ctf;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;

/** That this module does not grow its own version of something RainsCore owns. */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/ctf");
    }
}
