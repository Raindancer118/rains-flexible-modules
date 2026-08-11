package de.raindancer.modules.rtp;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module.
 *
 * <p>Everything is {@link WordingContract}'s — the two ways a tag reaches a player instead of a
 * colour, and why neither of them fails a build on its own. This says only where to look, because a
 * rule copied into every module is a rule that is true in two of them by March.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/rtp");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/rtp/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 5;
    }
}
