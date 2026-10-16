package de.raindancer.modules.ctf;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/** The wording rules, kept by this module. See {@code WordingContract} for what is checked. */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/ctf");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/ctf/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 120;
    }

    @Override
    public int fewestSourceFiles() {
        return 50;
    }
}
