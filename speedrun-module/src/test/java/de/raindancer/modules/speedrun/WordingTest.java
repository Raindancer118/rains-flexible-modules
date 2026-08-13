package de.raindancer.modules.speedrun;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module. See {@code chained-module}'s own {@code WordingTest} and
 * {@link WordingContract} itself for what this actually checks.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/speedrun");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/speedrun/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 5;
    }
}
