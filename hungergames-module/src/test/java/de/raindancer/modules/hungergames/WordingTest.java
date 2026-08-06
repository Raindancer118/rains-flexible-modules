package de.raindancer.modules.hungergames;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

/**
 * The wording rules, kept by this module.
 *
 * <p>Everything is {@link WordingContract}'s — the two ways a tag reaches a player instead of a colour, the
 * signature that stops a line wearing another plugin's brand, and why none of them fails a build on its own.
 * This says only where to look, because a rule copied into three modules is a rule that is true in two of
 * them by March.
 *
 * <p>Worth more here than in most modules: these lines are read by forty people at once, on a screen, while
 * something irreversible happens. A tag printed instead of a colour is noticed by everybody simultaneously.
 */
class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/hungergames");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/hungergames/messages.yml");
    }

    /**
     * The announcements, and no more yet.
     *
     * <p>Deliberately not the default: this module's wording arrives in waves as the services that send it are
     * ported, and a threshold set for the finished module would fail every build until the last one landed —
     * which trains people to lower it, which is how the check stops meaning anything. Raised as the wording
     * grows.
     */
    @Override
    public int fewestWordingLines() {
        return 37;
    }
}
