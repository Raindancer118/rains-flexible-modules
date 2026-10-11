package de.raindancer.modules.sends;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module. See {@code WordingContract} for what is actually
 * checked; this only says where to look.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/sends");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/sends/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 5;
    }
}
