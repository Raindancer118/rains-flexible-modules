package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;

/**
 * Something that <em>does</em> what a chat line asks for.
 *
 * <p>Every service takes its settings through {@link #settings(ChatSettings)} whether or not it
 * currently reads anything — the one forgotten when it starts reading something keeps yesterday's
 * numbers until the next restart, and that gets reported as "the config does not work".
 */
public interface IChatService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(ChatSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
