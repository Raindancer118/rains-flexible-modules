package de.raindancer.modules.chat;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module.
 *
 * <p>Everything is {@link WordingContract}'s — the two ways a tag reaches a player instead of a
 * colour, and why neither of them fails a build on its own. This says only where to look.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/chat");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/chat/messages.yml");
    }
}
