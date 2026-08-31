package de.raindancer.modules.manhunt;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module — see {@code chained-module}'s own copy of this class and
 * {@link WordingContract} itself for what these actually check.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/manhunt");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/manhunt/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 5;
    }
}
