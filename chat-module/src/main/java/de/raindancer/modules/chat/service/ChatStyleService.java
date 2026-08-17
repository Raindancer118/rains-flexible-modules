package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.ChatStyle;
import de.raindancer.modules.chat.store.ChatStyleStore;

import java.util.UUID;

/**
 * Who has chosen a colour or a decoration for their own chat messages — the picker's half of
 * {@link FormatService}, which asks this for the style and applies it.
 *
 * <p>Reads no {@link ChatSettings} of its own, unlike every other service in this package: a
 * player's own choice is not an owner-configured template, it is exactly the personal preference
 * {@link ChatSettings}'s own class note already says is deliberately not settled there.
 */
public final class ChatStyleService implements IChatService {

    private final ChatStyleStore store;

    public ChatStyleService(ChatStyleStore store) {
        this.store = store;
    }

    /** What this player has chosen, or {@link ChatStyle#DEFAULT} if they never have. */
    public ChatStyle styleOf(UUID who) {
        return store.of(who);
    }

    /** Remembers a choice, and writes it out straight away. */
    public void set(UUID who, ChatStyle style) {
        store.set(who, style);
    }

    @Override
    public void settings(ChatSettings settings) {
        // Nothing here reads the owner's settings — see the class note.
    }

    @Override
    public String describe() {
        return "who has chosen to have their own chat messages coloured, and how";
    }
}
