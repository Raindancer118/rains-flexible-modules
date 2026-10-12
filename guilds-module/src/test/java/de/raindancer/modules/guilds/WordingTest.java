package de.raindancer.modules.guilds;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/** The wording rules, kept by this module. See {@code WordingContract} for what is checked. */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/guilds");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/guilds/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 100;
    }

    @Override
    public int fewestSourceFiles() {
        return 40;
    }
}
