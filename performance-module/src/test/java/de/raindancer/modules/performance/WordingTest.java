package de.raindancer.modules.performance;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module. See {@code WordingContract} for what is actually
 * checked; this only says where to look.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/performance");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/performance/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 5;
    }

    /** A small module: a dozen files is all of it, and a scan finding fewer has lost track of them. */
    @Override
    public int fewestSourceFiles() {
        return 8;
    }
}
